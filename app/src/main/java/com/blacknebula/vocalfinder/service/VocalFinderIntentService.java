package com.blacknebula.vocalfinder.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.support.annotation.RequiresApi;
import android.view.Display;

import com.blacknebula.vocalfinder.R;
import com.blacknebula.vocalfinder.VocalFinderApplication;
import com.blacknebula.vocalfinder.activity.AlarmActivity;
import com.blacknebula.vocalfinder.activity.MainActivity;
import com.blacknebula.vocalfinder.activity.SettingsActivity;
import com.blacknebula.vocalfinder.util.Logger;
import com.blacknebula.vocalfinder.util.PreferenceUtils;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;

import org.parceler.Parcels;

import java.util.concurrent.TimeUnit;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.io.android.AudioDispatcherFactory;
import be.tarsos.dsp.pitch.PitchDetectionHandler;
import be.tarsos.dsp.pitch.PitchDetectionResult;
import be.tarsos.dsp.pitch.PitchProcessor;

import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class VocalFinderIntentService extends NonStopIntentService {

    //Actions
    public static final String START_ACTION = "detect_sound";
    public static final String KILL_ACTION = "kill";
    public static final String ALARM_STOP_ACTION = "stopAlarm";
    public static final String SOUND_DETECTED_ACTION = "soundDetected";
    public static final String PREFERENCES_UPDATE_ACTION = "preferencesUpdate";

    // Extras
    public static final String SOUND_PITCH_EXTRA = "reply";
    private static final int NOTIFICATION_ID = 1;

    private static final int MAX_VOLUME = -1;

    public static boolean isRunning;
    private static boolean isAlarmStarted;
    private static NotificationTypeEnum currentNotificationType;
    /**
     * Start without a delay, Vibrate for 100 milliseconds, Sleep for 1000 milliseconds
     */
    long[] vibrationPattern = {0, 100, 1000};
    private CameraManager mCameraManager;
    private String mCameraId;
    private boolean isTorchOn;
    private boolean isScreenBrightnessOn;
    private boolean isVolumeMaxed;
    private Vibrator vibrator;
    private Ringtone ringtone;
    private Stopwatch stopwatch;
    private int screenBrightness;
    private int currentVolume;
    private boolean isAlarmActivityOpened;

    public VocalFinderIntentService() {
        super(VocalFinderIntentService.class.getSimpleName());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        stopwatch = Stopwatch.createUnstarted();
        sendNotification(getNotificationType());
    }

    private void sendNotification(NotificationTypeEnum notificationType) {
        if (isAlarmStarted || notificationType.equals(currentNotificationType)) {
            return;
        }

        currentNotificationType = notificationType;

        final Intent mainIntent = new Intent(VocalFinderApplication.getAppContext(), MainActivity.class);
        final PendingIntent pReceiverIntent = PendingIntent.getActivity(VocalFinderApplication.getAppContext(), 1, mainIntent, 0);

        final Intent settingsIntent = new Intent(VocalFinderApplication.getAppContext(), SettingsActivity.class);
        final PendingIntent pSettings = PendingIntent.getActivity(VocalFinderApplication.getAppContext(), 1, settingsIntent, 0);

        final Notification.Builder builder = new Notification.Builder(VocalFinderApplication.getAppContext())
                .setSmallIcon(getNotificationSmallIcon(notificationType))
                .setColor(Color.WHITE)
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
                .setOngoing(true)
                .setContentTitle("Vocal finder")
                .setContentText("Never lose your phone again!")
                .setContentIntent(pReceiverIntent)
                .addAction(R.mipmap.ic_settings, "", pSettings);

        if (NotificationTypeEnum.ALARM.equals(notificationType)) {
            final Intent stopAlarmIntent = new Intent(VocalFinderApplication.getAppContext(), VocalFinderIntentService.class);
            stopAlarmIntent.setAction(ALARM_STOP_ACTION);
            final PendingIntent pStopAlarm = PendingIntent.getService(VocalFinderApplication.getAppContext(), 1, stopAlarmIntent, FLAG_CANCEL_CURRENT);
            builder.addAction(R.mipmap.ic_stop, "", pStopAlarm);
        } else {
            final Intent killIntent = new Intent(VocalFinderApplication.getAppContext(), VocalFinderIntentService.class);
            killIntent.setAction(KILL_ACTION);
            final PendingIntent pKill = PendingIntent.getService(VocalFinderApplication.getAppContext(), 1, killIntent, FLAG_CANCEL_CURRENT);
            builder.addAction(R.mipmap.ic_close, "", pKill);
        }

        final Notification notification = builder.build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private int getNotificationSmallIcon(NotificationTypeEnum notificationType) {
        if (NotificationTypeEnum.APP_DISABLE.equals(notificationType)) {
            return R.mipmap.ic_hold;
        } else if (NotificationTypeEnum.ENERGY_SAVE.equals(notificationType)) {
            return R.mipmap.ic_energy;
        } else if (NotificationTypeEnum.ALARM.equals(notificationType)) {
            return R.mipmap.ic_alarm;
        }

        return R.mipmap.ic_launcher;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            final String action = intent.getAction();
            if (START_ACTION.equals(action)) {
                detectSound();
            } else if (KILL_ACTION.equals(action)) {
                stopForeground(true);
                stopSelf();
                final NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                notificationManager.cancel(NOTIFICATION_ID);
            } else if (ALARM_STOP_ACTION.equals(action)) {
                stopAlarm();
                sendNotification(getNotificationType());
            } else if (PREFERENCES_UPDATE_ACTION.equals(intent.getAction())) {
                sendNotification(getNotificationType());
            }

        } catch (Exception ex) {
            Logger.error(Logger.Type.VOCAL_FINDER, ex, "Error handling action");
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        super.onDestroy();
    }

    // getting camera parameters
    private String getCamera() {
        if (mCameraId != null) {
            return mCameraId;
        }
        mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            mCameraId = mCameraManager.getCameraIdList()[0];
        } catch (CameraAccessException e) {
            Logger.error(Logger.Type.VOCAL_FINDER, e);
            PreferenceUtils.getPreferences().edit().putBoolean("enableFlashLight", false).apply();
        }
        return mCameraId;
    }

    Vibrator getVibrator() {
        // Get instance of Vibrator from current Context
        if (vibrator == null) {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
        return vibrator;

    }

    void detectSound() {
        final AudioDispatcher dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(22050, 1024, 0);

        PitchDetectionHandler pdh = new PitchDetectionHandler() {
            @Override
            public void handlePitch(PitchDetectionResult result, AudioEvent e) {
                if (!isRunning) {
                    dispatcher.stop();
                    Logger.warn(Logger.Type.VOCAL_FINDER, "Vocal finder service has been stopped");
                    return;
                }

                final float pitchInHz = result.getPitch();
                broadcastPitch(pitchInHz);

                if (skipSoundDetection()) {
                    sendNotification(getNotificationType());
                    return;
                }

                final boolean stopAlarmOnSoundEnd = shouldStopAlarmOnSoundEnd();
                if (shouldStartAlarm(pitchInHz)) {
                    // add stop alarm action in notification
                    if (!stopAlarmOnSoundEnd) {
                        sendNotification(NotificationTypeEnum.ALARM);
                    }
                    // start alarm
                    startAlarm();
                } else {
                    if (stopAlarmOnSoundEnd) {
                        stopAlarm();
                    }
                }
            }
        };
        AudioProcessor p = new PitchProcessor(PitchProcessor.PitchEstimationAlgorithm.FFT_YIN, 22050, 1024, pdh);
        dispatcher.addAudioProcessor(p);
        new Thread(dispatcher, "Audio Dispatcher").start();
    }

    private boolean shouldStartAlarm(float pitchInHz) {
        final int minimalPitch = PreferenceUtils.getIntFromString("audioSensitivity", getResources().getInteger(R.integer.pref_audio_sensitivity_default));

        if (pitchInHz < minimalPitch) {
            stopwatch.reset();
            return false;
        }

        if (!stopwatch.isRunning()) {
            stopwatch.start();
        }
        final int signalDuration = PreferenceUtils.getIntFromString("signalDuration", getResources().getInteger(R.integer.pref_signal_duration_default));
        return stopwatch.elapsed(TimeUnit.SECONDS) >= signalDuration;

    }

    private boolean shouldStopAlarmOnSoundEnd() {
        final String onSoundEndPrefValue = getResources().getString(R.string.sound_end_value);
        final String notificationEnd = PreferenceUtils.getString("notificationEnd", onSoundEndPrefValue);
        return onSoundEndPrefValue.equals(notificationEnd);
    }

    private void startAlarm() {
        if (isAlarmStarted) {
            return;
        }

        // change alarm status to true
        isAlarmStarted = true;

        // start all kinds of alarms
        maximizeVolume();
        playRingtone();
        turnOnFlashLight();
        startVibration();
        maximizeScreenBrightness();
        openAlarmActivity();
    }


    private void stopAlarm() {
        // stop all kinds of alarms
        resetVolume();
        stopVibration();
        turnOffFlashLight();
        stopRingtone();
        resetScreenBrightness();
        broadcastStopAlarm();
        // change alarm status to false
        isAlarmStarted = false;
    }

    /**
     * return true if at least one of these cases are true:
     * - app has been disabled
     * - save energy mode is enabled
     * - a call is active
     *
     * @return boolean
     */
    private boolean skipSoundDetection() {
        final boolean isEnabled = PreferenceUtils.getBoolean("enableVocalFinder", getResources().getBoolean(R.bool.enableVocalFinder_default));
        final boolean isSaveEnergyMode = PreferenceUtils.getBoolean("enableSaveEnergyMode", getResources().getBoolean(R.bool.enableSaveEnergyMode_default));
        return !isEnabled ||
                (isSaveEnergyMode && isScreenOn(VocalFinderApplication.getAppContext())) ||
                isCallActive(VocalFinderApplication.getAppContext());
    }

    private NotificationTypeEnum getNotificationType() {
        final boolean isEnabled = PreferenceUtils.getBoolean("enableVocalFinder", getResources().getBoolean(R.bool.enableVocalFinder_default));
        if (!isEnabled) {
            return NotificationTypeEnum.APP_DISABLE;
        }

        final boolean isSaveEnergyMode = PreferenceUtils.getBoolean("enableSaveEnergyMode", getResources().getBoolean(R.bool.enableSaveEnergyMode_default));
        if (isSaveEnergyMode) {
            return NotificationTypeEnum.ENERGY_SAVE;
        }
        return NotificationTypeEnum.NORMAL;
    }

    /**
     * Check if a telephony call is established.
     *
     * @param context context
     * @return true if telephony call is established
     */
    public boolean isCallActive(Context context) {
        final AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        return AudioManager.MODE_IN_CALL == manager.getMode();
    }

    /**
     * Is the screen of the device on.
     *
     * @param context the context
     * @return true when (at least one) screen is on
     */
    public boolean isScreenOn(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            boolean screenOn = false;
            for (Display display : dm.getDisplays()) {
                if (display.getState() != Display.STATE_OFF) {
                    screenOn = true;
                }
            }
            return screenOn;
        } else {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            //noinspection deprecation
            return pm.isScreenOn();
        }
    }

    public void turnOnFlashLight() {
        final boolean enableFlashLight = PreferenceUtils.getBoolean("enableFlashLight", getResources().getBoolean(R.bool.enableFlashLight_default));
        if (isTorchOn || !enableFlashLight)
            return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                final String cameraId = getCamera();
                if (cameraId != null) {
                    mCameraManager.setTorchMode(cameraId, true);
                    isTorchOn = true;
                }
            }
        } catch (Exception e) {
            Logger.error(Logger.Type.VOCAL_FINDER, e);
        }
    }

    public void turnOffFlashLight() {
        if (!isTorchOn)
            return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                final String cameraId = getCamera();
                if (cameraId != null) {
                    mCameraManager.setTorchMode(getCamera(), false);
                    isTorchOn = false;
                }
            }

        } catch (Exception e) {
            Logger.error(Logger.Type.VOCAL_FINDER, e);
        }
    }

    void startVibration() {
        final boolean enableVibration = PreferenceUtils.getBoolean("enableVibration", getResources().getBoolean(R.bool.enableVibration_default));
        if (enableVibration) {
            final Vibrator vibrator = getVibrator();
            if (vibrator != null) {
                vibrator.vibrate(vibrationPattern, 0);
            }
        }
    }

    void stopVibration() {
        final Vibrator vibrator = getVibrator();
        if (vibrator != null) {
            vibrator.cancel();
        }
    }


    void playRingtone() {
        final String selectedRingtone = PreferenceUtils.getString("ringtone", getString(R.string.ringtone_default));
        if (!Strings.isNullOrEmpty(selectedRingtone) && (ringtone == null || !ringtone.isPlaying())) {
            final Uri path = Uri.parse(selectedRingtone);
            ringtone = RingtoneManager.getRingtone(getApplicationContext(), path);
            ringtone.play();
        }
    }

    void stopRingtone() {
        if (ringtone != null && ringtone.isPlaying()) {
            ringtone.stop();
        }
    }

    void maximizeScreenBrightness() {
        final boolean isEnabled = PreferenceUtils.getBoolean("maximizeScreenBrightness", getResources().getBoolean(R.bool.maximizeScreenBrightness_default));
        if (isScreenBrightnessOn || !isEnabled) {
            return;
        }

        screenBrightness = getScreenBrightness();
        setScreenBrightness(255);
        isScreenBrightnessOn = true;
    }

    void resetScreenBrightness() {
        if (!isScreenBrightnessOn) {
            return;
        }

        setScreenBrightness(screenBrightness);
        isScreenBrightnessOn = false;
    }

    void maximizeVolume() {
        final boolean isEnabled = PreferenceUtils.getBoolean("maximizeVolume", getResources().getBoolean(R.bool.maximizeVolume_default));
        if (isVolumeMaxed || !isEnabled) {
            return;
        }
        currentVolume = getVolume();
        setVolume(MAX_VOLUME);
        isVolumeMaxed = true;
    }

    void resetVolume() {
        if (!isVolumeMaxed) {
            return;
        }

        setVolume(currentVolume);
        isVolumeMaxed = false;
    }

    private void openAlarmActivity() {
        // skip if activity already opened or alarm is configured to stop on sound end
        if (isAlarmActivityOpened || shouldStopAlarmOnSoundEnd()) {
            return;
        }

        isAlarmActivityOpened = true;
        final Intent intent = new Intent(VocalFinderApplication.getAppContext(), AlarmActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private <T> void broadcastStopAlarm() {
        if (!isAlarmActivityOpened) {
            return;
        }

        final Intent intent = new Intent();
        intent.setAction(ALARM_STOP_ACTION);
        sendBroadcast(intent);
        isAlarmActivityOpened = false;
    }


    /**
     * Get the screen current brightness
     */
    private int getScreenBrightness() {
        return Settings.System.getInt(
                VocalFinderApplication.getAppContext().getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS,
                0
        );
    }

    /**
     * Change the screen brightness
     *
     * @param brightnessValue value between 0..255
     */
    private void setScreenBrightness(int brightnessValue) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(VocalFinderApplication.getAppContext())) {
                // disable maximizeScreenBrightness
                PreferenceUtils.getPreferences().edit().putBoolean("maximizeScreenBrightness", false).apply();
                return;
            }
        }

        // Make sure brightness value between 0 to 255
        if (brightnessValue >= 0 && brightnessValue <= 255) {
            Settings.System.putInt(
                    VocalFinderApplication.getAppContext().getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS,
                    brightnessValue
            );
        }
    }

    private int getVolume() {
        final AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        return am.getStreamVolume(AudioManager.STREAM_RING);
    }

    private void setVolume(int volume) {
        final AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int value = volume == MAX_VOLUME ? am.getStreamMaxVolume(AudioManager.STREAM_RING) : volume;
        am.setStreamVolume(
                AudioManager.STREAM_RING,
                value,
                0);
    }

    private <T> void broadcastPitch(T replyMessage) {
        final Intent intent = new Intent();
        intent.setAction(SOUND_DETECTED_ACTION);
        intent.putExtra(SOUND_PITCH_EXTRA, Parcels.wrap(replyMessage));
        sendBroadcast(intent);
    }

}