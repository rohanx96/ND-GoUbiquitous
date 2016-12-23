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

package com.example.android.sunshine.app;

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
import android.graphics.drawable.BitmapDrawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.SurfaceHolder;
import android.view.WindowInsets;


import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    public static final String WEATHER_PATH = "/sunshine-weather";
    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        GoogleApiClient mGoogleApiClient;
        boolean mRegisteredTimeZoneReceiver = false;
        Bitmap mBackgroundBitmap;
        Bitmap mScaledBitmap;
        Paint mTextPaint;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;

        Paint mDatePaint;
        Paint mHighTempPaint;
        Paint mLowTempPaint;

        String mHighTemp;
        String mLowTemp;
        Bitmap mIcon;

        String[] dayOfWeek;
        String[] months;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundBitmap = ((BitmapDrawable) getResources().getDrawable(R.drawable.wearbg)).getBitmap();

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_text));
            mDatePaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size));

            mHighTempPaint = new Paint();
            mHighTempPaint = createTextPaint(resources.getColor(R.color.high_temp));
            mHighTempPaint.setTextSize(resources.getDimension(R.dimen.digital_temp_text_size));

            mLowTempPaint = new Paint();
            mLowTempPaint = createTextPaint(resources.getColor(R.color.low_temp));
            mLowTempPaint.setTextSize(resources.getDimension(R.dimen.digital_temp_text_size));

            mCalendar = Calendar.getInstance();
            dayOfWeek = new DateFormatSymbols().getShortWeekdays();
            months = new DateFormatSymbols().getShortMonths();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                mGoogleApiClient.connect();
                invalidate();
            } else {
                unregisterReceiver();
                mGoogleApiClient.disconnect();
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
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint.setTextSize(textSize);
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
                if(mAmbient){
                    mDatePaint.setColor(Color.GRAY);
                    mTextPaint.setColor(Color.WHITE);
                    mLowTempPaint.setColor(Color.GRAY);
                    mHighTempPaint.setColor(Color.GRAY);
                }
                else {
                    mDatePaint.setColor(getColor(R.color.digital_text));
                    mTextPaint.setColor(getColor(R.color.digital_text));
                    mLowTempPaint.setColor(getColor(R.color.low_temp));
                    mHighTempPaint.setColor(getColor(R.color.high_temp));
                }
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            float centerX = bounds.centerX();
            float centerY = bounds.centerY();
            if (mScaledBitmap == null) {
                mScaledBitmap = Bitmap.createScaledBitmap(mBackgroundBitmap, bounds.width(), bounds.height(), true);
            }
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawBitmap(mScaledBitmap, 0, 0, null);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            Resources resources = getResources();
            String text = mAmbient
                    ? String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE))
                    : String.format("%d:%02d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));
            canvas.drawText(text, centerX - (mTextPaint.measureText(text)/2), mYOffset, mTextPaint);

            String date = String.format("%s, %s %d, %d", dayOfWeek[mCalendar.get(Calendar.DAY_OF_WEEK)],
                    months[mCalendar.get(Calendar.MONTH)], mCalendar.get(Calendar.DATE), mCalendar.get(Calendar.YEAR));
            canvas.drawText(date, centerX - mDatePaint.measureText(date)/2,
                    mYOffset - resources.getDimension(R.dimen.date_y_offset),mDatePaint);
            if(mHighTemp != null && mLowTemp !=  null){
                float yOffset =  mYOffset + resources.getDimension(R.dimen.temp_y_offset);
                if(!mAmbient && mIcon!=null)
                    canvas.drawBitmap(mIcon, centerX - resources.getDimension(R.dimen.icon)/2, centerY,null);
                float xOffset = centerX - resources.getDimension(R.dimen.temp_offset_x) - mLowTempPaint.measureText(mLowTemp);
                canvas.drawText(mLowTemp, xOffset, yOffset ,mLowTempPaint);
                canvas.drawText(mHighTemp, centerX + resources.getDimension(R.dimen.temp_offset_x),yOffset , mHighTempPaint);
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

        /** Google client and data listener callback methods */
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

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo(WEATHER_PATH) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        mHighTemp = dataMap.getString("high-temp");
                        mLowTemp = dataMap.getString("low-temp");
                        new GetBitmapForWeatherTask().execute(dataMap.getAsset("icon"));
                        invalidate();
                    }
                }
            }
        }

        class GetBitmapForWeatherTask extends AsyncTask<Asset, Void, Void> {

            @Override
            protected Void doInBackground(Asset... assets) {
                Asset asset = assets[0];
                if (asset == null)
                    return null;

                ConnectionResult result = mGoogleApiClient.blockingConnect(500, TimeUnit.MILLISECONDS);
                if (!result.isSuccess())
                    return null;

                // convert asset into a file descriptor and block until it's ready
                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(mGoogleApiClient, asset).await().getInputStream();

                if (assetInputStream == null)
                    return null;

                // decode the stream into a bitmap
                mIcon = BitmapFactory.decodeStream(assetInputStream);
                int size = (int) getResources().getDimension(R.dimen.icon);
                mIcon = Bitmap.createScaledBitmap(mIcon, size, size, false);
                postInvalidate();

                return null;
            }
        }

    }
}
