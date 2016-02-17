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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineFace extends CanvasWatchFaceService implements GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {
    private static final String LOG_TAG = SunshineFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final String MIN_TEMP_KEY = "com.example.android.sunshine.data.min_temp";
    private static final String MAX_TEMP_KEY = "com.example.android.sunshine.data.max_temp";
    private static final String WEATHER_ID_KEY = "com.example.android.sunshine.data.weather_id";

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    private GoogleApiClient mGoogleApiClient;

    int lowTemp = -1;
    int highTemp = -1;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    @Override
    public void onConnected(Bundle bundle) {
        Log.d(LOG_TAG, "onConnected(): Successfully connected to Google API client");
        Wearable.DataApi.addListener(mGoogleApiClient, this);
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(LOG_TAG, "Google API connection was suspended");
    }


    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                // DataItem changed
                DataItem item = event.getDataItem();
                if (item.getUri().getPath().compareTo("/weather") == 0) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    updateWeather(
                            dataMap.getInt(MIN_TEMP_KEY),
                            dataMap.getInt(MAX_TEMP_KEY),
                            dataMap.getInt(WEATHER_ID_KEY));
                }
            }
        }
    }

    private void updateWeather(int low, int high, int weatherId) {
        Log.i(LOG_TAG, " received data : (" + low + ", " + high + "), " + weatherId);
        lowTemp = low;
        highTemp = high;
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.d(LOG_TAG, "Google API connection failed!");
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineFace.Engine> mWeakReference;

        public EngineHandler(SunshineFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mDatePaint;
        Paint mHTPaint;
        Paint mLTPaint;
        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        float mYOffset;
        float mDateYOffset;
        float mTempOffset;

        Paint mWeatherPaint;
        Bitmap mWeatherBitmap;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_offset);
            mDateYOffset = resources.getDimension(R.dimen.digital_date_offset);
            mTempOffset = resources.getDimension(R.dimen.digital_temp_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mDatePaint = createDatePaint(resources.getColor(R.color.digital_text));
            mHTPaint = createHighTempPaint(resources.getColor(R.color.digital_text));
            mLTPaint = createLowTempPaint(resources.getColor(R.color.digital_text));


            mTime = new Time();

            Drawable weatherDrawable = resources.getDrawable(R.drawable.art_clear);
            mWeatherBitmap = ((BitmapDrawable) weatherDrawable).getBitmap();

            mWeatherPaint = new Paint();

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineFace.this)
                    .addConnectionCallbacks(SunshineFace.this)
                    .addOnConnectionFailedListener(SunshineFace.this)
                    .addApi(Wearable.API) // tell Google API that we want to use Warable API
                    .build();
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
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createDatePaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setAlpha(175);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createHighTempPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createLowTempPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setAlpha(175);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();

                mGoogleApiClient.connect();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Log.d(LOG_TAG, "removing listeners");
                    Wearable.DataApi.removeListener(mGoogleApiClient, SunshineFace.this);
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
            SunshineFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineFace.this.getResources();
            boolean isRound = insets.isRound();
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float dateSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_size : R.dimen.digital_date_size_round);
            float tempSize = resources.getDimension(R.dimen.digital_temp_size);
            mTextPaint.setTextSize(textSize);
            mDatePaint.setTextSize(dateSize);
            mHTPaint.setTextSize(tempSize);
            mLTPaint.setTextSize(tempSize);
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
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM
            mTime.setToNow();
            String text = String.format("%d:%02d", mTime.hour, mTime.minute);
            canvas.drawText(text, bounds.centerX(), mYOffset, mTextPaint);

            if (!isInAmbientMode()) {
                canvas.drawText(mTime.format("%a, %b %d %Y"), bounds.centerX(), mDateYOffset, mDatePaint);
                canvas.drawLine(bounds.centerX() - 25, mDateYOffset + 20, bounds.centerX() + 25, mDateYOffset + 20, mDatePaint);

                //TODO the following should only be visible after we receive weather msg
                // low/high temperatures
                canvas.drawText(lowTemp+"˚", bounds.centerX(), mTempOffset, mHTPaint);
                canvas.drawText(highTemp+"˚", (int)(bounds.width() * .7), mTempOffset, mLTPaint);

                // draw weather graphic
                Matrix mtx = new Matrix();
                mtx.setScale(.4f, .4f);
                mtx.postTranslate(bounds.width()*.2f, mTempOffset-40);
                canvas.drawBitmap(mWeatherBitmap, mtx, mWeatherPaint);
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
    }
}
