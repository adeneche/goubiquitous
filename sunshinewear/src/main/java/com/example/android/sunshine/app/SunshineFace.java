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

    int lowTemp = 0;
    int highTemp = 0;
    int weatherId = 0;
    boolean receivedData = false;

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
        this.weatherId = weatherId;
        receivedData = true;
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
        float[] dimensions;
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

        Paint mWeatherPaint;
        Bitmap mWeatherBitmap;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        int weatherId = -1;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineFace.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mDatePaint = createDatePaint(resources.getColor(R.color.digital_text));
            mHTPaint = createHighTempPaint(resources.getColor(R.color.digital_text));
            mLTPaint = createLowTempPaint(resources.getColor(R.color.digital_text));


            mTime = new Time();

            loadIconResourceForWeatherCondition(weatherId);

            mWeatherPaint = new Paint();

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineFace.this)
                    .addConnectionCallbacks(SunshineFace.this)
                    .addOnConnectionFailedListener(SunshineFace.this)
                    .addApi(Wearable.API) // tell Google API that we want to use Warable API
                    .build();
        }

        private void loadIconResourceForWeatherCondition(int weatherId) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            int icon = -1;

            if (weatherId >= 200 && weatherId <= 232) {
                icon = R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                icon = R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                icon = R.drawable.ic_rain;
            } else if (weatherId == 511) {
                icon = R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                icon = R.drawable.ic_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                icon = R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                icon = R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                icon = R.drawable.ic_storm;
            } else if (weatherId == 800) {
                icon = R.drawable.ic_clear;
            } else if (weatherId == 801) {
                icon = R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                icon = R.drawable.ic_cloudy;
            }

            mWeatherBitmap = null;
            if (icon != -1) {
                Drawable weatherDrawable = getResources().getDrawable(icon);
                mWeatherBitmap = ((BitmapDrawable) weatherDrawable).getBitmap();
            }
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
//            paint.setTextAlign(Paint.Align.CENTER);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createLowTempPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setAlpha(175);
            paint.setTypeface(NORMAL_TYPEFACE);
//            paint.setTextAlign(Paint.Align.CENTER);
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
            dimensions = getDimensions(insets.isRound());

            mTextPaint.setTextSize(dimensions[TIME_TXT_SIZE]);
            mDatePaint.setTextSize(dimensions[DATE_TXT_SIZE]);
            mHTPaint.setTextSize(dimensions[TEMPERATURE_TXT_SIZE]);
            mLTPaint.setTextSize(dimensions[TEMPERATURE_TXT_SIZE]);
        }

        private static final int TIME_TXT_SIZE = 0;
        private static final int DATE_TXT_SIZE = 1;
        private static final int TEMPERATURE_TXT_SIZE = 2;
        private static final int TIME_TXT_Y = 3;
        private static final int DATE_TXT_Y = 4;
        private static final int LINE_Y = 5;
        private static final int LINE_WIDTH = 6;
        private static final int LOW_TEMP_TXT_X = 7;
        private static final int HIGH_TEMP_TXT_X = 8;
        private static final int TEMPERATURE_TXT_Y = 9;
        private static final int WEATHER_ICON_X = 10;
        private static final int WEATHER_ICON_Y = 11;

        private float[] getDimensions(boolean isRound) {
            float[] dimensions = new float[12];
            Resources resources = SunshineFace.this.getResources();

            if (isRound) {
                dimensions[TIME_TXT_SIZE] = resources.getDimension(R.dimen.time_txt_size_round);
                dimensions[DATE_TXT_SIZE] = resources.getDimension(R.dimen.date_txt_size_round);
                dimensions[TEMPERATURE_TXT_SIZE] = resources.getDimension(R.dimen.temperature_txt_size_round);
                dimensions[TIME_TXT_Y] = resources.getDimension(R.dimen.time_txt_y_round);
                dimensions[DATE_TXT_Y] = resources.getDimension(R.dimen.date_txt_y_round);
                dimensions[LINE_Y] = resources.getDimension(R.dimen.line_y_round);
                dimensions[LINE_WIDTH] = resources.getDimension(R.dimen.line_width_round);
                dimensions[LOW_TEMP_TXT_X] = resources.getDimension(R.dimen.low_temp_txt_x_round);
                dimensions[HIGH_TEMP_TXT_X] = resources.getDimension(R.dimen.high_temp_txt_x_round);
                dimensions[TEMPERATURE_TXT_Y] = resources.getDimension(R.dimen.temperature_txt_y_round);
                dimensions[WEATHER_ICON_X] = resources.getDimension(R.dimen.weather_icon_x_round);
                dimensions[WEATHER_ICON_Y] = resources.getDimension(R.dimen.weather_icon_y_round);
            } else {
                dimensions[TIME_TXT_SIZE] = resources.getDimension(R.dimen.time_txt_size);
                dimensions[DATE_TXT_SIZE] = resources.getDimension(R.dimen.date_txt_size);
                dimensions[TEMPERATURE_TXT_SIZE] = resources.getDimension(R.dimen.temperature_txt_size);
                dimensions[TIME_TXT_Y] = resources.getDimension(R.dimen.time_txt_y);
                dimensions[DATE_TXT_Y] = resources.getDimension(R.dimen.date_txt_y);
                dimensions[LINE_Y] = resources.getDimension(R.dimen.line_y);
                dimensions[LINE_WIDTH] = resources.getDimension(R.dimen.line_width);
                dimensions[LOW_TEMP_TXT_X] = resources.getDimension(R.dimen.low_temp_txt_x);
                dimensions[HIGH_TEMP_TXT_X] = resources.getDimension(R.dimen.high_temp_txt_x);
                dimensions[TEMPERATURE_TXT_Y] = resources.getDimension(R.dimen.temperature_txt_y);
                dimensions[WEATHER_ICON_X] = resources.getDimension(R.dimen.weather_icon_x);
                dimensions[WEATHER_ICON_Y] = resources.getDimension(R.dimen.weather_icon_y);
            }

            return dimensions;
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
            canvas.drawText(text, bounds.centerX(), dimensions[TIME_TXT_Y], mTextPaint);

            if (!isInAmbientMode()) {
                canvas.drawText(mTime.format("%a, %b %d %Y"), bounds.centerX(), dimensions[DATE_TXT_Y], mDatePaint);
                canvas.drawLine(bounds.centerX() - dimensions[LINE_WIDTH]/2, dimensions[LINE_Y],
                        bounds.centerX() + dimensions[LINE_WIDTH]/2, dimensions[LINE_Y], mDatePaint);

                if (receivedData) {
                    // low/high temperatures
                    canvas.drawText(lowTemp + "˚", dimensions[LOW_TEMP_TXT_X], dimensions[TEMPERATURE_TXT_Y], mHTPaint);
                    canvas.drawText(highTemp + "˚", dimensions[HIGH_TEMP_TXT_X], dimensions[TEMPERATURE_TXT_Y], mLTPaint);

                    if (weatherId != SunshineFace.this.weatherId) {
                        weatherId = SunshineFace.this.weatherId;
                        loadIconResourceForWeatherCondition(weatherId);
                    }

                    if (mWeatherBitmap != null) {
                        // draw weather graphic
                        canvas.drawBitmap(mWeatherBitmap, dimensions[WEATHER_ICON_X], dimensions[WEATHER_ICON_Y], mWeatherPaint);
                    }
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
    }
}
