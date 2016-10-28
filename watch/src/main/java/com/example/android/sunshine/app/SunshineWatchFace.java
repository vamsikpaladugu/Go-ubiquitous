
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
        Paint mTextTimePaint, mTextDatePaint, mTextHighTPaint;
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

        String High_temp = "Sun", Low_temp = " Shine";
        String time = "";
        String date = "";

        //watch bounds

        Double x0 = 0.146, y0 = 0.146;  //top left corner


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

            //for draw time
            mTextTimePaint = new Paint();
            mTextTimePaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTextTimePaint.setTextSize(resources.getDimension(R.dimen.digital_text_size_round));


            //for draw date
            mTextDatePaint = new Paint();
            mTextDatePaint = createTextPaint(resources.getColor(R.color.digital_date));

            mTextDatePaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size1));


            //for draw temp
            mTextHighTPaint = new Paint();


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
                    //        mTextDatePaint.setAntiAlias(!inAmbientMode);
                    //        mTextTimePaint.setAntiAlias(!inAmbientMode);
                    //        mTextHighTPaint.setAntiAlias(!inAmbientMode);
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


            mCalendar.setTimeInMillis(System.currentTimeMillis());


            //time
            time = String.format("%02d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY), mCalendar.get(Calendar.MINUTE));

            Rect rTime = new Rect();

            canvas.getClipBounds(rTime);
            int cWidth = rTime.width();
            mTextTimePaint.setTextAlign(Paint.Align.LEFT);
            mTextTimePaint.getTextBounds(time, 0, time.length(), rTime);
            float x = cWidth / 2f - rTime.width() / 2f - rTime.left;
            float y = ((float) (y0 * canvas.getHeight())) + rTime.height() - rTime.bottom;
            canvas.drawText(time, x, y, mTextTimePaint);

            float time_height = ((float) (y0 * canvas.getHeight())) + rTime.height() + rTime.bottom + 10;


            //date
            date = new SimpleDateFormat("EEE, MMM dd").format(mCalendar.getTimeInMillis());

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

                rect.set(0, canvas.getHeight() / 2, canvas.getWidth(), canvas.getHeight() + canvas.getHeight() / 2);

                paint1.setColor(Color.WHITE);
                paint1.setAntiAlias(true);
                paint1.setStrokeCap(Paint.Cap.ROUND);
                paint1.setStyle(Paint.Style.FILL);

                canvas.drawArc(rect, -90, 360, false, paint1);


                canvas.save();
                Resources res = getResources();
                Bitmap bitmap = BitmapFactory.decodeResource(res, drawWeatherIcon);

                int cx = (canvas.getWidth() - bitmap.getWidth()) / 2;

                int cy = canvas.getHeight() / 2;

                canvas.drawBitmap(bitmap, cx, cy, null);
                canvas.restore();

            }

            //draw High temp


            if (!isInAmbientMode()) {
                mTextHighTPaint = createTextPaint(resources.getColor(R.color.digital_temp_high));
            } else {
                mTextHighTPaint = createTextPaint(resources.getColor(R.color.digital_date));
            }

            mTextHighTPaint.setTextSize(resources.getDimension(R.dimen.digital_temphigh_text_size));


            Rect rTemp = new Rect();

            canvas.getClipBounds(rTemp);

            mTextHighTPaint.setTextAlign(Paint.Align.LEFT);
            mTextHighTPaint.getTextBounds(High_temp, 0, High_temp.length(), rTemp);

            x = canvas.getWidth() / 2f - rTemp.width() + rTemp.left - 5;
            y = canvas.getHeight() * 3 / 4f + rTemp.height();

            canvas.drawText(High_temp, x, y, mTextHighTPaint);


            //draw low temp

            mTextHighTPaint = new Paint();
            mTextHighTPaint = createTextPaint(resources.getColor(R.color.digital_date));

            mTextHighTPaint.setTextSize(resources.getDimension(R.dimen.digital_temphigh_text_size));


            rTemp = new Rect();

            canvas.getClipBounds(rTemp);

            mTextHighTPaint.setTextAlign(Paint.Align.LEFT);
            mTextHighTPaint.getTextBounds(Low_temp, 0, Low_temp.length(), rTemp);

            x = canvas.getWidth() / 2f - rTemp.left + 5;

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
            //same as sunshine
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
