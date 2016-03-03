package com.rjfun.cordova.httpd;

import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.apache.cordova.CordovaWebView;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Enumeration;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

public class WebServer extends NanoHTTPD
{
	private final String LOGTAG = "WebServer";
	private Boolean _allowDirectoryListing;
	private AndroidFile _rootDirectory;

    private CordovaWebView _webView;

    private String _javascriptHandlerFunctionString = null;
    private ResponseCallback _onserverResponse = new ResponseCallback();

	public WebServer(InetSocketAddress localAddr, AndroidFile wwwroot, Boolean allowDirectoryListing, CordovaWebView webView) throws IOException {
		super(localAddr, wwwroot);
        this._rootDirectory=wwwroot;
		this._allowDirectoryListing = allowDirectoryListing;
        this._webView = webView;
	}

	public WebServer(int port, AndroidFile wwwroot, Boolean allowDirectoryListing, CordovaWebView webView) throws IOException {
		super(port, wwwroot);
        this._rootDirectory=wwwroot;
		this._allowDirectoryListing = allowDirectoryListing;
        this._webView = webView;
	}

    //Sets a callback that will serve response which is defined in JavaScript
    public void setResponseCallback(String responseHandler){
        this._javascriptHandlerFunctionString = responseHandler;
    }

    public Void onServeEvent(final JSONObject parameters) {
        Log.d(LOGTAG, "onServeEvent: " + (_javascriptHandlerFunctionString == null ? "No Callback" : "will call back!"));
        if(_javascriptHandlerFunctionString != null) {
            CountDownLatch signal = new CountDownLatch(1); //1 to wait
            _onserverResponse.setSignal(signal);
            Log.i(LOGTAG, "---------------------- Signal set");
            try{
                final CordovaWebView webView = this._webView;
                //Stupid cordova hides precious Android WebView
                final WebView androidWebView = (WebView)webView.getEngine().getView();
                final String jsHandler = this._javascriptHandlerFunctionString;
                androidWebView.post(new Runnable() {
                    @Override
                    public void run() {
                        androidWebView.evaluateJavascript("(" + jsHandler + ")("+parameters.toString()+");", new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String returnedValue) {
                                Log.d(LOGTAG, "VALUE RECVD from JAVASCRIPT"+returnedValue);
                                try {
                                    //returnValue is null when JS returns void, "null" when returned value is JS null
                                    if(returnedValue == null || returnedValue.equals("null")){
                                        //Will cause the server to try to find a file on the disk with normal static behavior
                                        _onserverResponse.setNullResponse();
                                    }else{
                                        JSONObject returnedParams = new JSONObject(returnedValue);
                                        _onserverResponse.setParams(returnedParams);
                                    }
                                }
                                catch(Exception ex){
                                    Log.e(LOGTAG,"Error parsing returned value: "+ex.toString());
                                }
                                _onserverResponse._signal.countDown();
                            }
                        });
                    }
                });
                signal.await();
                Log.i(LOGTAG, "++++++++++++++++++++++ Signal UNLOCKED!");
            }
            catch(Exception ex){
                Log.e(LOGTAG, "Signal broken "+ex.toString());
            }

        }
        return null;
    }

	@Override
	public Response serve( String uri, String method, Properties header, Properties parms, Properties files )
	{
		Log.i(LOGTAG, method + " '" + uri + "' ");

        //Dynamic behavior
        if(this._javascriptHandlerFunctionString!=null){
            try{
                JSONObject cbParams = new JSONObject();
                cbParams.put("uri",uri);
                cbParams.put("method",method);
                Enumeration e = parms.propertyNames();
                JSONObject properties = new JSONObject();
                while (e.hasMoreElements()){
                    String value = (String)e.nextElement();
                    properties.put(value,parms.getProperty(value));
                }
                cbParams.put("properties",properties);

                e = header.propertyNames();
                JSONObject headers = new JSONObject();
                while (e.hasMoreElements()){
                    String value = (String)e.nextElement();
                    headers.put(value,header.getProperty(value));
                }
                cbParams.put("headers",headers);

                e = files.propertyNames();
                JSONObject _files = new JSONObject();
                while (e.hasMoreElements()){
                    String value = (String)e.nextElement();
                    _files.put(value,files.getProperty(value));
                }
                cbParams.put("files", _files);
                Log.d(LOGTAG, "Calling Javascript callback ");
                //Call javascript and return request information
                onServeEvent(cbParams);
                Response res = _onserverResponse.call();
                if(res == null){ //Try static behavior
                    return super.serveFile(uri, header, _rootDirectory, _allowDirectoryListing);
                }
                else{
                    return res;
                }
            }
            catch(Exception ex){
                Log.e(LOGTAG,"Exception invoking callback: "+ ex.toString());
                return new Response(HTTP_INTERNALERROR,MIME_PLAINTEXT,"Internal Server error: "+ ex.toString() );
            }
        }
        //Static behavior
        else{
            return super.serveFile(uri, header, _rootDirectory, _allowDirectoryListing);
        }
	}
}
