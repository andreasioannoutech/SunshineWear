package com.example.android.sunshine.utilities;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.util.Log;

import com.android.annotations.NonNull;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Created by kikkos on 12/29/2016.
 */

public class SunshineWearUtils implements ConnectionCallbacks, OnConnectionFailedListener{

    public static final String WEATHER_DATA_PATH = "/weather-data";
    private static final String TAG = "WearUtils";

    private Context mContext;
    private double highOld = -9999;
    private double highNew = -9999;
    private double lowOld = -9999;
    private double lowNew = -9999;
    private int conditionIdOld = -9999;
    private int conditionIdNew = -9999;
    private GoogleApiClient mGoogleApiClient;

    public SunshineWearUtils(Context context){
        this.mContext = context;
        mGoogleApiClient = new GoogleApiClient.Builder(mContext)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
        mGoogleApiClient.connect();
    }

    public void insertOldWeather(@NonNull double high, @NonNull double low, @NonNull int conditionId){
        this.highOld = high;
        this.lowOld = low;
        this.conditionIdOld = conditionId;
    }

    public void insertNewWeather(@NonNull double high, @NonNull double low, @NonNull int conditionId){
        this.highNew = high;
        this.lowNew = low;
        this.conditionIdNew = conditionId;

        // will send weather data to wear device only if they have changed..
        if (hasChanged()){
            Log.v("TEST", "check finished");
            sendWeatherData();
        }
    }

    public boolean hasChanged(){
        if (highOld != highNew || lowOld != lowNew || conditionIdOld != conditionIdNew){
            return true;
        }
        return false;
    }

    public void sendWeatherData(){
        // get the icon as a bitmap
        int drawableIcon = SunshineWeatherUtils.getSmallArtResourceIdForWeatherCondition(conditionIdNew);
        Bitmap icon = Bitmap.createScaledBitmap(BitmapFactory.decodeResource(mContext.getResources(), drawableIcon), 70, 70, true);

        PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEATHER_DATA_PATH);
        putDataMapRequest.getDataMap().putString("high", SunshineWeatherUtils.formatTemperature(mContext,highNew));
        putDataMapRequest.getDataMap().putString("low", SunshineWeatherUtils.formatTemperature(mContext, lowNew));
        putDataMapRequest.getDataMap().putAsset("icon", toAsset(icon));
        putDataMapRequest.getDataMap().putLong("timestamp", System.currentTimeMillis());

        PutDataRequest request = putDataMapRequest.asPutDataRequest();
        request.setUrgent();
        Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                    @Override
                    public void onResult(@android.support.annotation.NonNull DataApi.DataItemResult dataItemResult) {
                        Log.d(TAG, "sending weather data was successful: " + dataItemResult.getStatus().isSuccess());
                    }
                });
    }

    private static Asset toAsset(Bitmap bitmap) {
        ByteArrayOutputStream byteStream = null;
        try {
            byteStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
            return Asset.createFromBytes(byteStream.toByteArray());
        } finally {
            if (null != byteStream) {
                try {
                    byteStream.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onConnected: " + bundle);
        }
    }

    @Override
    public void onConnectionSuspended(int i) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onConnectionSuspended: " + i);
        }
    }

    @Override
    public void onConnectionFailed(@android.support.annotation.NonNull ConnectionResult connectionResult) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onConnectionFailed: " + connectionResult);
        }
    }
}
