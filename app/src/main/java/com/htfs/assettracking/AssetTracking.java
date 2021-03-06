package com.htfs.assettracking;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.location.Location;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.htfs.intentservice.GetaddressService;
import com.htfs.utilpackage.CheckDataSourceConnection;
import com.htfs.utilpackage.CheckInternetConnection;
import com.htfs.utilpackage.Checkloactionprovider;
import com.htfs.utilpackage.DialogBoxDisplay;
import com.nispok.snackbar.Snackbar;
import com.nispok.snackbar.listeners.ActionClickListener;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * AssetTracking Activity
 * <p>
 * User scan assert, reads the current location,
 * take picture of the asset and upload to server
 * </p>
 */
public class AssetTracking extends Activity implements TextWatcher, LocationListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    //<editor-fold desc="Variables">
    /**
     * UI objects
     */
    private Button assetSubmitBtn, getPictureBtn, scanBarcode;
    private CheckBox needLocationCB;
    private EditText assetCodeTx, assetuserTx, assetdateTx, assetCommnetTx, assetName;
    private ImageView assetpic;
    private ProgressDialog progressDialog;
    private Bitmap astImage;
    private Typeface typeface;
    /**
     * Network variables
     */
    private String POSTDATA_URL = "";
    private String POST_IMG = "";
    private String POST_ASSETDET = "";
    private GoogleApiClient mGoogleApiClient;
    private LocationRequest mLocationRequest;
    /**
     * Activity variables
     */
    private SharedPreferences sp, sharedPreferencesDB;
    private static final String TAG = "AT1";
    private static final int GET_IMG_REQ = 100;
    /**
     * Task variables
     */
    private String locationAddress = "";
    private double assetlatitute, assetlogitude;
    private String currentpicname;
    private File fimg, temppath;
    private boolean isassetpresent = false;
    private SimpleDateFormat sd;
    private String baseAddress;

    /**
     * Receiver object
     * <p>Receive the message from Broadcast</p>
     */
    private ResponseReceiver responseReceiver;
    //</editor-fold>

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_asset_tracking);
        /**
         * Check for configuration
         * else
         * Prompt user for configuration
         */
        sharedPreferencesDB = getSharedPreferences(Login.DB_CONFIG, MODE_PRIVATE);
        if (sharedPreferencesDB.contains(Login.DBSTRING)) {
            baseAddress = sharedPreferencesDB.getString(Login.DBSTRING, null);
            POSTDATA_URL = baseAddress + "AssetManagement/Services/AssetLocationHistory/SetAssetLocation";
            POST_IMG = baseAddress + "AssetManagement/Services/AssetLocationHistory/Upload";
            POST_ASSETDET = baseAddress + "AssetManagement/Services/AssetLocationHistory/GetAssetDetails/";
        } else {
            Toast.makeText(this, "No Configuration Found", Toast.LENGTH_LONG).show();
            Intent i = new Intent(this, Configuration.class);
            startActivity(i);
            finish();
        }

        //Building google api client
        buildGoogleApiClient();

        //Initializing receiver class
        IntentFilter filter = new IntentFilter(ResponseReceiver.ACTION_RESP);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        responseReceiver = new ResponseReceiver();
        registerReceiver(responseReceiver, filter);

        /**
         * Exit from app when user press back
         * <p>Optional</p>
         * @see this.onBackPressed()
         */
        if (getIntent().getBooleanExtra("EXIT", false)) {
            finish();
            return;
        }

        typeface = Typeface.createFromAsset(getAssets(), "Dolce Vita.ttf");
        assetSubmitBtn = (Button) findViewById(R.id.astconfirm);
        assetSubmitBtn.setOnClickListener(confirmAsset);

        getPictureBtn = (Button) findViewById(R.id.astretakepic);
        getPictureBtn.setOnClickListener(getAssetImage);

        scanBarcode = (Button) findViewById(R.id.astscanCode);
        scanBarcode.setOnClickListener(scanAssetsCode);
        scanBarcode.setTypeface(typeface);


        needLocationCB = (CheckBox) findViewById(R.id.astgetLoction);
        needLocationCB.setOnCheckedChangeListener(checkforLocation);

        assetCodeTx = (EditText) findViewById(R.id.astCode);
        assetCodeTx.setEnabled(false);
        assetCodeTx.addTextChangedListener(this);
        assetName = (EditText) findViewById(R.id.astname);
        assetName.setEnabled(false);

        assetuserTx = (EditText) findViewById(R.id.assetregUsername);

        assetdateTx = (EditText) findViewById(R.id.asttakendate);
        assetdateTx.setEnabled(false);
        sd = new SimpleDateFormat("yyyy-MM-dd");
        assetdateTx.setText(sd.format(Calendar.getInstance().getTime()));
        assetCommnetTx = (EditText) findViewById(R.id.astcomment);
        assetpic = (ImageView) findViewById(R.id.astviewpic);

        this.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        sp = getSharedPreferences(Login.USER_SP, MODE_PRIVATE);
    }

    /**
     * Build api client to receive location update and address of location
     * Fused location provider
     */
    private synchronized void buildGoogleApiClient() {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
    }

    /**
     * To display snackbar (like Toast)
     *
     * @param context  Activity context
     * @param activity activity
     */
    private void displaySnackbar(final Context context, final Activity activity) {
        Snackbar.with(context)
                .text("Check Internet Connection")
                .duration(Snackbar.SnackbarDuration.LENGTH_INDEFINITE)
                .actionLabel("Try Again")
                .actionListener(new ActionClickListener() {
                    @Override
                    public void onActionClicked(Snackbar snackbar) {
                        if (CheckInternetConnection.checkConnection(context)) {
                            snackbar.dismiss();
                        }
                    }
                })
                .swipeToDismiss(false)
                .show(activity);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!CheckInternetConnection.checkConnection(getApplicationContext())) {
            displaySnackbar(getApplicationContext(), this);
        }
        // Connect to build google API Client
        mGoogleApiClient.connect();

        if (sp.contains(Login.USERNAME)) {
            Log.d(TAG, "contains");
            assetuserTx.setText(sp.getString("USERNAME", ""));
        }

        if (!Checkloactionprovider.checklocationService(this))
            DialogBoxDisplay.showGps(this);

        Log.d(TAG, String.valueOf(mGoogleApiClient.isConnected()));
        Log.d(TAG, "ON Resume:" + "|| asset latitude:" + assetlatitute + " asset longitude:" + assetlogitude);
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onPause() {
        super.onPause();
        //disconnect Google api Client onPause
        if (mGoogleApiClient.isConnected())
            stopLocationUpdates();
    }


    //<editor-fold desc="Scan and Picture Task">
    /**
     * Take picture from device camera and store in sdCard and in FILE
     * for future upload of asset Image
     */
    private View.OnClickListener getAssetImage = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            // Asset Image name
            currentpicname = "ASSET_" + assetCodeTx.getText().toString() + "_" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + ".jpg";
            temppath = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Asset");
            if (!temppath.exists())
                temppath.mkdirs();
            fimg = new File(temppath, currentpicname);
            Log.i("Path", fimg.getAbsolutePath());
            //Image cature intent with created filepath as Extra
            if (fimg != null) {
                Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                i.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(fimg));
                i.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
                startActivityForResult(i, GET_IMG_REQ);
            }
        }
    };

    /**
     * Scan asset using barcode scanner
     */
    private View.OnClickListener scanAssetsCode = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            IntentIntegrator integrator = new IntentIntegrator(AssetTracking.this);
            integrator.initiateScan();
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == GET_IMG_REQ) {
            if (resultCode == RESULT_OK) {
                if (fimg != null) {
                    // Image capture is successful & save in mediaStore, set image to ImageView
                    astImage = encodeImgFile(fimg);
                    MediaStore.Images.Media.insertImage(getContentResolver(), astImage, currentpicname, assetCodeTx.getText().toString());
                    assetpic.setImageBitmap(astImage);
                }
            } else if (resultCode == RESULT_CANCELED) {
                // User cancelled the image capture
            } else {
                // Image capture failed, advise user
            }
        }

        // IntentResult for barcode scanner
        IntentResult intentResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (intentResult != null) {
            //Scan successful
            Log.d("Scan Result", intentResult.toString());
            assetCodeTx.setText(intentResult.getContents());
            getAssetDetails(intentResult.getContents());
        } else {
            //Failed Scan
        }

    }

    /**
     * Reduce the file quality by time four
     *
     * @param imagefile
     * @return Bitmap
     */
    private Bitmap encodeImgFile(File imagefile) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(imagefile.getAbsolutePath(), options);
        options.inSampleSize = 4;
        options.inJustDecodeBounds = false;
        Log.i("In", "encodeImgFile Method");
        return BitmapFactory.decodeFile(imagefile.getAbsolutePath(), options);
    }
    //</editor-fold>


    private CompoundButton.OnCheckedChangeListener checkforLocation = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
            Log.d(TAG, String.valueOf(b));
            if (!b) {
                Log.d(TAG, "Checked false");
                stopLocationUpdates();
                assetlatitute = 0.0;
                assetlogitude = 0.0;
            } else {
                Log.d(TAG, "Checked true");
                startLocationUpdates();
            }
        }
    };

    // Confirm button onClickListener
    private View.OnClickListener confirmAsset = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            if (isassetpresent) {
                if (!assetCodeTx.getText().toString().equals("") && !assetdateTx.getText().toString().equals("")) {
                    uploadDatatoServer();
                    Log.d(TAG, "assetlatitute:" + assetlatitute + "long:" + assetlogitude);
                } else {
                    if (assetCodeTx.getText().toString().equals(""))
                        assetCodeTx.setError("Scan asset before submitting");
                    if (assetdateTx.getText().toString().equals(""))
                        assetdateTx.setError("Select the date");
                }
            } else {
                DialogBoxDisplay.msgBox(AssetTracking.this, "Asset is not present so cannot submit");
            }
        }
    };

//region get Asset Details

    /**
     * Get asset details for the scanned code
     * <p>
     * The anonymous async task i/p asset code gets asset imformation from server
     * </p>
     *
     * @param assetCode
     */
    private void getAssetDetails(String assetCode) {
        new AsyncTask<String, Void, String>() {
            @Override
            protected void onPreExecute() {
                super.onPreExecute();
                progressDialog = new ProgressDialog(AssetTracking.this);
                progressDialog.setIndeterminate(true);
                progressDialog.setTitle("Please wait");
                progressDialog.setMessage("Getting asset detail");
                progressDialog.setCancelable(false);
                progressDialog.show();
            }

            @Override
            protected void onPostExecute(String aVoid) {
                super.onPostExecute(aVoid);
                if (progressDialog != null) {
                    progressDialog.dismiss();
                    progressDialog = null;
                }
                if (aVoid == null) {
                    Log.d(TAG, "null");
                } else if (aVoid.equals("int")) {
                    DialogBoxDisplay.noInte(AssetTracking.this);
                } else if (aVoid.equals("res")) {
                    Log.d(TAG, "res");
                    DialogBoxDisplay.msgBox(AssetTracking.this, "Asset not found");
                } else {
                    assetName.setText(aVoid);
                    isassetpresent = true;
                }
            }

            @Override
            protected String doInBackground(String... strings) {
                Log.d(TAG, "IN BACKGROUNG TASK");
                StringBuilder sb = new StringBuilder();
                try {
                    URL url = new URL(POST_ASSETDET + "" + strings[0]);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setReadTimeout(10000);
                    connection.setConnectTimeout(15000);
                    connection.setRequestMethod("POST");
                    connection.setDoInput(true);
                    connection.setDoOutput(true);
                    connection.setUseCaches(false);
                    connection.connect();
                    int HttpRes = connection.getResponseCode();
                    if (HttpRes == HttpURLConnection.HTTP_OK) {
                        Log.d(TAG, "200");
                        BufferedReader br = new BufferedReader(new InputStreamReader(
                                connection.getInputStream(), "utf-8"));
                        String line = null;
                        while ((line = br.readLine()) != null) {
                            sb.append(line).append("\n");
                        }
                        br.close();
                        JSONObject resObject = new JSONObject(sb.toString());
                        if (resObject.has("assetDescription"))
                            return resObject.getString("assetDescription");
                        else if (resObject.has("reason")) {
                            Log.d(TAG, "reason---" + resObject.getString("reason"));
                            return "res";
                        }
                    } else if (HttpRes == HttpURLConnection.HTTP_INTERNAL_ERROR) {
                        Log.d(TAG, "500");
                        return "int";
                    } else if (HttpRes == 404) {
                        Log.d(TAG, "404");
                        return "int";
                    }

                } catch (MalformedURLException | SocketTimeoutException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                    return "int";
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return null;
            }
        }.execute(assetCode);
    }
//endregion


    //<editor-fold desc="Network Upload Task">

    /**
     * Async task to uplaod the asset information to server
     * upon complete onPostExecute the image is uploded at sepreate Async
     */
    private void uploadDatatoServer() {
        new AsyncTask<Void, Void, String>() {
            @Override
            protected void onPreExecute() {
                super.onPreExecute();
                progressDialog = new ProgressDialog(AssetTracking.this);
                progressDialog.setIndeterminate(true);
                progressDialog.setTitle("Uploading Please Wait");
                progressDialog.setCancelable(false);
                progressDialog.show();
            }

            @Override
            protected void onPostExecute(String aVoid) {
                super.onPostExecute(aVoid);
                progressDialog.dismiss();
                progressDialog = null;
                if (aVoid == null) {
                    Log.d(TAG, "null");
                } else if (aVoid.equals("int")) {
                    DialogBoxDisplay.noInte(AssetTracking.this);
                } else if (aVoid.equals("res")) {
                    Log.d(TAG, "res");
                }
                if (astImage != null)
                    uploadImagetoServer(fimg.getAbsolutePath());
                else
                    clearAll();
            }

            @Override
            protected String doInBackground(Void... voids) {
                Log.d(TAG, "IN BACKGROUNG TASK");
                StringBuilder sb = new StringBuilder();
                try {
                    URL url = new URL(POSTDATA_URL);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setReadTimeout(10000);
                    connection.setConnectTimeout(15000);
                    connection.setRequestMethod("POST");
                    connection.setDoInput(true);
                    connection.setDoOutput(true);
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Content-Type", "application/json");
                    connection.connect();
                    JSONObject dataPram = new JSONObject();
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
                    sdf.setTimeZone(TimeZone.getDefault());
                    if (!assetuserTx.getText().toString().equals(""))
                        dataPram.put("username", assetuserTx.getText().toString());
                    else
                        dataPram.put("username", "");
                    dataPram.put("assetcode", assetCodeTx.getText().toString());
                    dataPram.put("datetime", sdf.format(Calendar.getInstance().getTime()));
                    dataPram.put("comments", assetCommnetTx.getText().toString());
                    dataPram.put("gps", assetlatitute + "," + assetlogitude);
                    dataPram.put("location", locationAddress);
                    dataPram.put("timezone", TimeZone.getDefault().getID());
                    if (fimg != null)
                        dataPram.put("filename", fimg.getName());
                    Log.d(TAG, "[JSON DATA]--" + dataPram.toString());
                    DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
                    outputStream.writeBytes(dataPram.toString());
                    outputStream.flush();
                    outputStream.close();
                    int HttpRes = connection.getResponseCode();
                    if (HttpRes == HttpURLConnection.HTTP_OK) {
                        Log.d(TAG, "200");
                        BufferedReader br = new BufferedReader(new InputStreamReader(
                                connection.getInputStream(), "utf-8"));
                        String line = null;
                        while ((line = br.readLine()) != null) {
                            sb.append(line).append("\n");
                        }
                        br.close();
                        JSONObject resObject = new JSONObject(sb.toString());
                        if (resObject.has("reason"))
                            return resObject.getString("reason");
                    } else if (HttpRes == HttpURLConnection.HTTP_INTERNAL_ERROR) {
                        Log.d(TAG, "500");
                        return "int";
                    } else if (HttpRes == 404) {
                        Log.d(TAG, "404");
                        return "int";
                    }

                } catch (MalformedURLException | SocketTimeoutException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                    return "int";
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return null;
            }
        }.execute();
    }

    /**
     * Upload asset's image to server Async
     *
     * @param sourceFileUri
     */
    private void uploadImagetoServer(String sourceFileUri) {
        new AsyncTask<Void, String, Integer>() {


            @Override
            protected void onPreExecute() {
                super.onPreExecute();
                progressDialog = new ProgressDialog(AssetTracking.this);
                progressDialog.setTitle("Uploading Please Wait");
                progressDialog.setCancelable(false);
                progressDialog.setIndeterminate(false);
                progressDialog.show();
            }

            @Override
            protected void onPostExecute(Integer aVoid) {
                super.onPostExecute(aVoid);
                progressDialog.dismiss();
                progressDialog = null;
                Log.d(TAG, String.valueOf(aVoid));
                if (aVoid == 200) {
                    DialogBoxDisplay.msgBox(AssetTracking.this, "Asset successfully submitted");
                    clearAll();
                } else {
                    DialogBoxDisplay.msgBox(AssetTracking.this, "Image wasn't uploaded");
                }
            }

            @Override
            protected void onProgressUpdate(String... values) {
                super.onProgressUpdate(values);

            }

            @Override
            protected Integer doInBackground(Void... params) {
                String boundary = "*****";
                String delimiter = "--";
                int bytesRead, bytesAvailable, bufferSize, serverResponseCode = 0;
                byte[] buffer;
                int maxBufferSize = 1 * 1024 * 1024;
                try {
                    HttpURLConnection con = (HttpURLConnection) (new URL(POST_IMG)).openConnection();
                    FileInputStream fileInputStream = new FileInputStream(fimg);
                    try {
                        con.setRequestMethod("POST");
                    } catch (ProtocolException e) {
                        Log.e("Err", "PROTOCOL");
                        e.printStackTrace();
                    }
                    con.setDoInput(true);
                    con.setDoOutput(true);
                    con.setRequestProperty("Connection", "Keep-Alive");
                    con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                    con.setRequestProperty("upfile", fimg.getName());
                    Log.e("FileName", fimg.getName());
                    con.setRequestProperty("file", fimg.getName());
                    DataOutputStream dos = new DataOutputStream(con.getOutputStream());
                    dos.writeBytes(delimiter + boundary + "\r\n");
                    dos.writeBytes("Content-Disposition: form-data; name=\"file\";filename=\"" + fimg.getName() + "\"" + "\r\n");
                    dos.writeBytes("\r\n");

                    bytesAvailable = fileInputStream.available();
                    bufferSize = Math.min(bytesAvailable, maxBufferSize);
                    buffer = new byte[bufferSize];
                    bytesRead = fileInputStream.read(buffer, 0, bufferSize);
                    while (bytesRead > 0) {
                        dos.write(buffer, 0, bufferSize);

                        progressDialog.setProgress((bytesRead / bytesAvailable) * 100);
                        bytesAvailable = fileInputStream.available();
                        bufferSize = Math.min(bytesAvailable, maxBufferSize);
                        bytesRead = fileInputStream.read(buffer, 0, bufferSize);
                    }
                    dos.writeBytes("\r\n");
                    dos.writeBytes(delimiter + boundary + delimiter + "\r\n");
                    serverResponseCode = con.getResponseCode();

                    fileInputStream.close();
                    dos.flush();
                    dos.close();
//                    con.disconnect();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return serverResponseCode;
            }
        }.execute();
    }
    //</editor-fold>


    //region GetLocationTask
    @Override
    public void onLocationChanged(Location location) {
        assetlatitute = location.getLatitude();
        assetlogitude = location.getLongitude();
        Log.d(TAG, "[LAT,LONG]--[" + assetlatitute + "," + assetlogitude + "]");
        startAddressServiceIntent();
    }

    @Override
    public void onConnected(Bundle bundle) {

        Log.d(TAG, "Locations Onconnected");
        createLocationRequest();
        startLocationUpdates();

    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {

    }

    /**
     * Start Address intent service
     *
     * @see GetaddressService
     */
    private void startAddressServiceIntent() {
        Intent getAddrIntent = new Intent(this, GetaddressService.class);
        getAddrIntent.putExtra(GetaddressService.PRAM_LAT, assetlatitute);
        getAddrIntent.putExtra(GetaddressService.PRAM_LONG, assetlogitude);
        startService(getAddrIntent);
    }

    private void startLocationUpdates() {
        Log.d(TAG, "On Start Location Update");
        LocationServices.FusedLocationApi.requestLocationUpdates(
                mGoogleApiClient, mLocationRequest, this);
    }

    /**
     * Setting location request interval time
     */
    private void createLocationRequest() {
        mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(10000);
        mLocationRequest.setFastestInterval(5000);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);
    }

    /**
     * Kill google api client
     */
    private void stopLocationUpdates() {
        LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, this);
    }

    //endregion

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_asset_tracking, menu);
        return true;
    }

    // On back pressed exit application
    @Override
    public void onBackPressed() {
        super.onBackPressed();
        Intent intent = new Intent(this, AssetTracking.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("EXIT", true);
        startActivity(intent);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        int id = item.getItemId();
        switch (id) {
            case R.id.logout: {
                if (sp == null)
                    sp = getSharedPreferences(Login.USER_SP, MODE_PRIVATE);
                SharedPreferences.Editor edit = sp.edit();
                edit.clear();
                edit.commit();
                Intent i = new Intent(this, Login.class);
                startActivity(i);
                this.finish();
                return true;

            }
            case R.id.CConfig: {
                if (sharedPreferencesDB == null)
                    sharedPreferencesDB = getSharedPreferences(Login.DB_CONFIG, MODE_PRIVATE);
                if (sharedPreferencesDB.contains(Login.DBSTRING)) {
                    CheckDataSourceConnection c = new CheckDataSourceConnection(this, sharedPreferencesDB.getString(Login.DBSTRING, ""), false);
                    c.check();
                } else {
                    Toast.makeText(this, "There is no configuration setting,\nClear configuration and re-enter ", Toast.LENGTH_LONG).show();
                }
                return true;
            }
            case R.id.RConfig: {
                if (sp == null)
                    sp = getSharedPreferences(Login.USER_SP, MODE_PRIVATE);
                if (sharedPreferencesDB == null)
                    sharedPreferencesDB = getSharedPreferences(Login.DB_CONFIG, MODE_PRIVATE);
                SharedPreferences.Editor edit = sp.edit();
                edit.clear();
                edit.commit();
                edit = sharedPreferencesDB.edit();
                edit.clear();
                edit.commit();
                Intent i = new Intent(this, Login.class);
                startActivity(i);
                this.finish();
                return true;

            }
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "On Destroy Called");
        this.unregisterReceiver(responseReceiver);
        super.onDestroy();
    }

    @Override
    public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

    }

    @Override
    public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

    }

    @Override
    public void afterTextChanged(Editable editable) {

        if (editable == assetCodeTx.getEditableText()) {
            if (!assetCodeTx.getText().toString().equals(""))
                assetCodeTx.setError(null);
        }
        if (editable == assetdateTx.getEditableText()) {
            if (!assetdateTx.getText().toString().equals(""))
                assetdateTx.setError(null);
        }
    }

    /**
     * Receiver class to receive the address of location from intent service
     */
    public class ResponseReceiver extends BroadcastReceiver {
        public static final String ACTION_RESP = "com.htfs.intent.action.MESSAGE_PROCESSED";

        @Override
        public void onReceive(Context context, Intent intent) {

            locationAddress = intent.getStringExtra(GetaddressService.ADDR);
            if (locationAddress.equals("error")) {
                displaySnackbar(getApplicationContext(), AssetTracking.this);
            }
            Log.d("ADDRESS FROM INTENT", intent.getStringExtra(GetaddressService.ADDR));
        }
    }

    /**
     * Clear all UI field after a successful update
     */
    private void clearAll() {
        assetCodeTx.setText("");
        assetName.setText("");
        assetdateTx.setText(sd.format(Calendar.getInstance().getTime()));
        assetCommnetTx.setText("");
        assetpic.setImageBitmap(null);
        astImage = null;
        fimg = null;
    }

}
