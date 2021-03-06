package com.htfs.utilpackage;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

/**
 * Created by Vinoth on 22-05-2015.
 */
public class CheckInternetConnection {

    /**
     * To check whether the device have internet connectivity
     *
     * @param context Called activity context
     * @return boolean
     */
    public static boolean checkConnection(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        if (ni == null) {
            Log.d("Check Network State", "FALSE");
            return false;
        } else {
            Log.d("Check Network State", "TRUE");
            return true;
        }
    }
}
