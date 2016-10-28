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
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;


import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
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

    private class Engine extends CanvasWatchFaceService.Engine
            implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {


        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextTimePaint, mTextDatePaint, mTextHighTPaint, mTextLowTPaint;
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

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        private GoogleApiClient googleApiClient;

        private static final String PATH_WEATHER = "/weather";
        private static final String KEY_TEMPHIGH = "high";
        private static final String KEY_TEMPLOW = "low";
        private static final String KEY_WEATHERID = "weatherId";


        int drawWeatherIcon = R.drawable.ic_clear;

        Resources resources;
        float textSize;

        String High_temp = "00", Low_temp = "00";


        //watch bounds

        Double x0 = 0.146, y0 = 0.146;  //top left corner

        Double x1 = 0.854, y1 = 0.146;  //top right corner

        Double x2 = 0.146, y2 = 0.854;  //bottom left corner

        Double x3 = 0.854, y3 = 0.854;  //bottom right corner


        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextTimePaint = new Paint();
            mTextTimePaint = createTextPaint(resources.getColor(R.color.digital_text));


            mCalendar = Calendar.getInstance();


            googleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();


        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);

            releaseGoogleApiClient();

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

                googleApiClient.connect();


                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {

                releaseGoogleApiClient();

                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**/
        private void releaseGoogleApiClient() {
            if (googleApiClient != null && googleApiClient.isConnected()) {

                googleApiClient.disconnect();
            }


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
            resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            /*float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);*/

            textSize = resources.getDimension(R.dimen.digital_text_size_round);

            //mTextPaint.setTextSize(textSize);

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
                    //mTextDatePaint.setAntiAlias(!inAmbientMode);
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

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);


            //time
            String time = String.format("%02d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY), mCalendar.get(Calendar.MINUTE));

            mTextTimePaint = new Paint();
            mTextTimePaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTextTimePaint.setTextSize(resources.getDimension(R.dimen.digital_text_size_round));


            Rect r = new Rect();

            canvas.getClipBounds(r);
            int cWidth = r.width();
            mTextTimePaint.setTextAlign(Paint.Align.LEFT);
            mTextTimePaint.getTextBounds(time, 0, time.length(), r);
            float x = cWidth / 2f - r.width() / 2f - r.left;
            //float y = cHeight / 2f + r.height() / 2f - r.bottom;
            float y = ((float) (y0 * canvas.getHeight())) + r.height() - r.bottom;
            canvas.drawText(time, x, y, mTextTimePaint);

            float time_height = ((float) (y0 * canvas.getHeight())) + r.height() + r.bottom + 10;


            //date

            String date = new SimpleDateFormat("EEE, MMM dd").format(mCalendar.getTimeInMillis());

            mTextDatePaint = new Paint();
            mTextDatePaint = createTextPaint(resources.getColor(R.color.digital_date));

            mTextDatePaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size1));


            Rect rDate = new Rect();

            canvas.getClipBounds(rDate);

            cWidth = rDate.width();

            mTextDatePaint.setTextAlign(Paint.Align.LEFT);
            mTextDatePaint.getTextBounds(date, 0, date.length(), rDate);
            x = cWidth / 2f - rDate.width() / 2f - rDate.left;
            y = time_height + rDate.height() - rDate.bottom;

            canvas.drawText(date, x, y, mTextDatePaint);


            //draw arc

            if (!isInAmbientMode()) {



                Paint paint1 = new Paint();
                final RectF rect = new RectF();
                //Example values
                rect.set(0, canvas.getHeight() / 2, canvas.getWidth(), canvas.getHeight() + canvas.getHeight() / 2);

                paint1.setColor(Color.WHITE);
                paint1.setAntiAlias(true);
                paint1.setStrokeCap(Paint.Cap.ROUND);
                paint1.setStyle(Paint.Style.FILL);
                //paint1.setShadowLayer(2f, 1f, 1f, Color.BLACK);

                canvas.drawArc(rect, -90, 360, false, paint1);


                canvas.save();
                Resources res = getResources();
                Bitmap bitmap = BitmapFactory.decodeResource(res, drawWeatherIcon);

                //int cx = (int) (x1*canvas.getWidth() - bitmap.getWidth());
                int cx = (canvas.getWidth() - bitmap.getWidth()) / 2;

                int cy = canvas.getHeight() / 2;//(int) tempHight;

                canvas.drawBitmap(bitmap, cx, cy, null);
                canvas.restore();

            }

            //draw High temp

            mTextHighTPaint = new Paint();

            if (!isInAmbientMode()) {
                mTextHighTPaint = createTextPaint(resources.getColor(R.color.digital_temp_high));
            } else {
                mTextHighTPaint = createTextPaint(resources.getColor(R.color.digital_date));
            }

            mTextHighTPaint.setTextSize(resources.getDimension(R.dimen.digital_temphigh_text_size));



            r = new Rect();

            canvas.getClipBounds(r);

            mTextHighTPaint.setTextAlign(Paint.Align.LEFT);
            mTextHighTPaint.getTextBounds(High_temp, 0, High_temp.length(), r);

            x = canvas.getWidth() / 2f - r.width() + r.left - 5;
            y = canvas.getHeight() * 3 / 4f + r.height();

            canvas.drawText(High_temp, x, y, mTextHighTPaint);


            //draw low temp

            mTextHighTPaint = new Paint();
            mTextHighTPaint = createTextPaint(resources.getColor(R.color.digital_date));

            mTextHighTPaint.setTextSize(resources.getDimension(R.dimen.digital_temphigh_text_size));


            r = new Rect();

            canvas.getClipBounds(r);

            mTextHighTPaint.setTextAlign(Paint.Align.LEFT);
            mTextHighTPaint.getTextBounds(Low_temp, 0, Low_temp.length(), r);

            x = canvas.getWidth() / 2f - r.left + 5;

            canvas.drawText(Low_temp, x, y, mTextHighTPaint);


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

            Log.v("Connected", "true");


            Wearable.DataApi.addListener(googleApiClient, new DataApi.DataListener() {
                @Override
                public void onDataChanged(DataEventBuffer dataEvents) {

                    Log.v("onDataChanged", "true");

                    for (DataEvent event : dataEvents) {
                        if (event.getType() == DataEvent.TYPE_CHANGED) {
                            DataItem item = event.getDataItem();
                            processConfigurationFor(item);
                        }


                    }

                    dataEvents.release();
                    //invalidateIfNecessary();


                }
            });


            Wearable.DataApi.getDataItems(googleApiClient).setResultCallback(new ResultCallback<DataItemBuffer>() {
                @Override
                public void onResult(@NonNull DataItemBuffer dataItems) {

                    for (DataItem item : dataItems) {
                        processConfigurationFor(item);
                    }

                    dataItems.release();
                    //invalidateIfNecessary();

                }
            });


        }


        private void processConfigurationFor(DataItem item) {
            if (PATH_WEATHER.equals(item.getUri().getPath())) {
                DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();

                if (dataMap.containsKey(KEY_TEMPHIGH)) {

                    High_temp = dataMap.getString(KEY_TEMPHIGH);

                }

                if (dataMap.containsKey(KEY_TEMPLOW)) {

                    Low_temp = dataMap.getString(KEY_TEMPLOW);

                }

                if (dataMap.containsKey(KEY_WEATHERID)) {

                    drawWeatherIcon = getIconResourceForWeatherCondition(dataMap.getInt(KEY_WEATHERID));

                }


                invalidate();

            }
        }


        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }


        public int getIconResourceForWeatherCondition(int weatherId) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.ic_rain;
            } else if (weatherId == 511) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.ic_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                return R.drawable.ic_storm;
            } else if (weatherId == 800) {
                return R.drawable.ic_clear;
            } else if (weatherId == 801) {
                return R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.ic_cloudy;
            }
            return -1;
        }

    }
}
