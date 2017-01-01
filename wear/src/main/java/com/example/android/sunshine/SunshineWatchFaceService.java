/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {
    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        private static final String TAG = "Engine";

        private final Typeface PRIMARY_TYPEFACE = Typeface.createFromAsset(getAssets(), "font/RobotoTTF/Roboto-Regular.ttf");
        private final Typeface SECONDARY_TYPEFACE = Typeface.createFromAsset(getAssets(), "font/RobotoTTF/RobotoCondensed-Light.ttf");

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mDatePaint;
        Paint mTempHighPaint;
        Paint mTempLowPaint;

        boolean mAmbient;

        Calendar mCalendar;
        String tempHigh = "40 ";
        String tempLow = "38";
        Bitmap mWeatherIcon = Bitmap.createScaledBitmap(BitmapFactory.decodeResource(getResources(), R.drawable.ic_clear), 70, 70, true);
        float mLineHeight;
        float mLineHeightNoSpacing;

        private GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFaceService.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTimePaint = createTextPaint(resources.getColor(R.color.primary_text), 1);
            mDatePaint = createTextPaint(resources.getColor(R.color.secondary_text), 2);
            mTempHighPaint = createTextPaint(resources.getColor(R.color.primary_text), 1);
            mTempLowPaint = createTextPaint(resources.getColor(R.color.secondary_text), 2);

            mLineHeight = resources.getDimension(R.dimen.digital_line_height);
            mLineHeightNoSpacing = resources.getDimension(R.dimen.digital_line_height_no_spacing);

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, int mode) {
            Paint paint = new Paint();
            switch (mode){
                case 1:{
                    paint.setTypeface(PRIMARY_TYPEFACE);
                    break;
                }
                default:{
                    paint.setTypeface(SECONDARY_TYPEFACE);
                    break;
                }
            }
            paint.setColor(textColor);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()){
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();

            mXOffset = resources.getDimension(R.dimen.digital_x_offset);
            mYOffset = resources.getDimension(isRound ? R.dimen.digital_y_offset : R.dimen.digital_y_offset_square);

            float timeTextSize = resources.getDimension(R.dimen.time_text_size);

            float weatherTextSize = resources.getDimension(R.dimen.weather_text_size);

            float dateTextSize = resources.getDimension(R.dimen.date_text_size);

            mTimePaint.setTextSize(timeTextSize);
            mDatePaint.setTextSize(dateTextSize);
            mTempHighPaint.setTextSize(weatherTextSize);
            mTempLowPaint.setTextSize(weatherTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            int width = bounds.width();

            float centerX = width / 2f;
            float x = mXOffset;
            float y = mYOffset;

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            // draw time
            String time = String.format("%d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY), mCalendar.get(Calendar.MINUTE));
            x = centerX - (mTimePaint.measureText(time) / 2);
            canvas.drawText(time, x, y, mTimePaint);

            if (!mAmbient){
                // draw date
                SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM dd yyyy");
                String date = sdf.format(mCalendar.getTime());
                y+= mLineHeightNoSpacing;
                x = centerX - (mDatePaint.measureText(date) / 2);
                canvas.drawText(date.toUpperCase(), x, y, mDatePaint);

                // draw line
                float lineStartX = centerX - (mTimePaint.measureText(time) / 2) + 40;
                float lineY = y + (mLineHeight / 2) - 10;
                float lineEndX = centerX + (mTimePaint.measureText(time) / 2) - 40;
                canvas.drawLine(lineStartX, lineY, lineEndX, lineY, mDatePaint);

                // draw weather icon followed by temperature
                if (mWeatherIcon != null){
                    // get total width of icon plus the 2 temperatures
                    float weatherTotalWidth = (float) mWeatherIcon.getWidth() + mTempHighPaint.measureText(tempHigh) + mTempLowPaint.measureText(tempLow);
                    // measure starting point on X-axis from the center of screen
                    x = centerX - (weatherTotalWidth / 2);
                    // calculate Y-axis position of bitmap icon
                    float bitmapY = y + mLineHeightNoSpacing - 5;
                    // draw icon
                    canvas.drawBitmap(mWeatherIcon, x, bitmapY, null);
                    // move X-axis starting point after the icon
                    x+= (float) mWeatherIcon.getWidth();
                    // calculate Y-axis position for temperature text
                    y+= mLineHeight + (mLineHeightNoSpacing / 2) - 10;
                    // draw temperatures
                    canvas.drawText(tempHigh, x, y, mTempHighPaint);
                    x+= mTempHighPaint.measureText(tempHigh);
                    canvas.drawText(tempLow, x, y, mTempLowPaint);
                }
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        // Getting the weather data from the mobile app.
        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.v(TAG, "onDataChanged: " + dataEventBuffer);
            for (DataEvent dataEvent : dataEventBuffer){
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED){
                    DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                    String path = dataEvent.getDataItem().getUri().getPath();
                    if (path.equals("/weather-data")){
                        Log.v(TAG, "sending weather data for drawing on canvas: " + dataMap);
                        updateUiWeatherData(dataMap.getString("high"), dataMap.getString("low"), dataMap.getAsset("icon"));
                    }
                }
            }
        }

        public void updateUiWeatherData(String high, String low, Asset icon){
            // change the high, low and icon values of the canvas and then invalidate
            // invalidate() will happen once the bitmap load is finished within the aync task called.
            tempHigh = high;
            tempLow = low;
            new LoadBitmapAsyncTask().execute(icon);
        }

        // task to get the bitmap from the assets
        private class LoadBitmapAsyncTask extends AsyncTask<Asset, Void, Bitmap>{
            @Override
            protected Bitmap doInBackground(Asset... params) {

                if (params.length > 0){
                    Asset asset = params[0];
                    InputStream assetInputStream = Wearable.DataApi.getFdForAsset(mGoogleApiClient, asset).await().getInputStream();
                    if (assetInputStream == null){
                        return null;
                    }
                    return BitmapFactory.decodeStream(assetInputStream);
                }else {
                    Log.e(TAG, "Asset must be non-null");
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if (bitmap != null){
                    Log.d(TAG, "Setting Weather Icon..");
                    mWeatherIcon = bitmap;
                    invalidate();
                    Log.v(TAG, "UI UPDATED");
                }
            }
        }
    }
}
