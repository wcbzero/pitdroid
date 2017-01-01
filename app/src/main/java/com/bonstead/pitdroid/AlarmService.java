package com.bonstead.pitdroid;

import com.bonstead.pitdroid.HeaterMeter.NamedSample;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

public class AlarmService extends Service
{
	static final String TAG = "AlarmService";
	static final String NAME = "com.bonstead.pitdroid.AlarmService";

	static final int kStatusNotificationId = 1;
	static final int kAlarmNotificationId = 2;

	private PendingIntent mServiceAlarm;

	@Override
	public void onCreate()
	{
		super.onCreate();

		// Create a pending intent use to schedule us for wakeups
		Intent alarmIntent = new Intent(this, AlarmService.class);
		mServiceAlarm = PendingIntent.getService(this, 0, alarmIntent, 0);
	}

	@Override
	public void onDestroy()
	{
		super.onDestroy();

		if (BuildConfig.DEBUG)
		{
			Log.v(TAG, "onDestroy");
		}

		cancelAlarm();
		mServiceAlarm = null;

		// Cancel the persistent notification.
		stopForeground(true);
	}

	private void scheduleAlarm()
	{
		HeaterMeter heatermeter = ((PitDroidApplication) getApplication()).mHeaterMeter;

		long nextTime = System.currentTimeMillis() + heatermeter.mBackgroundUpdateTime * 60 * 1000;
		AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
		{
			// It's important that even if the user isn't actively using their device the
			// checks will run, so use the version that will force a wakeup on newer
			// versions of Android with Doze mode.
			alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTime, mServiceAlarm);
		}
		else
		{
			alarmManager.set(AlarmManager.RTC_WAKEUP, nextTime, mServiceAlarm);
		}
	}

	private void cancelAlarm()
	{
		AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
		alarmManager.cancel(mServiceAlarm);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId)
	{
		if (BuildConfig.DEBUG)
		{
			Log.v(TAG, "onStartCommand");
		}

		// Acquire a wake lock to ensure the device doesn't go to sleep while we're querying the
		// HeaterMeter.  When the query thread is done it will release this.
		PowerManager mgr = (PowerManager) getBaseContext().getSystemService(Context.POWER_SERVICE);
		final WakeLock lock = mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, NAME);
		lock.acquire();

		// It's possible this is being called due to alarm settings changing and not the alarm going
		// off, so cancel any pending alarms before proceeding.
		cancelAlarm();

		updateStatusNotification(null, null);

		new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				if (BuildConfig.DEBUG)
				{
					Log.v(TAG, "Getting sample from HeaterMeter...");
				}

				HeaterMeter heatermeter = ((PitDroidApplication) getApplication()).mHeaterMeter;

				NamedSample sample = heatermeter.getSample();

				if (BuildConfig.DEBUG)
				{
					if (sample != null)
					{
						Log.v(TAG, "Got sample");
					}
					else
					{
						Log.v(TAG, "Sample was null");
					}
				}

				updateStatusNotification(sample, heatermeter);
				updateAlarmNotification(sample, heatermeter);

				scheduleAlarm();

				lock.release();
			}
		}).start();

		// We want this service to continue running until it is explicitly stopped, so return sticky.
		return START_STICKY;
	}

	/**
	 * Show a notification while this service is running.
	 */
	private void updateStatusNotification(final NamedSample latestSample, final HeaterMeter heatermeter)
	{
		if (BuildConfig.DEBUG)
		{
			Log.v(TAG, "Info notification");
		}

		String contentText = "";

		if (latestSample != null)
		{
			// If we've got a sample, check if any of the alarms are triggered
			for (int p = 0; p < HeaterMeter.kNumProbes; p++)
			{
				if (!Double.isNaN(latestSample.mProbes[p]))
				{
					if (contentText.length() > 0)
					{
						contentText += " ";
					}

					contentText += latestSample.mProbeNames[p] + ": ";

					contentText += heatermeter.formatTemperature(latestSample.mProbes[p]);
				}
			}
		}
		else
		{
			contentText = getString(R.string.alarm_service_info);
		}

		Intent mainIntent = new Intent(this, MainActivity.class);
		PendingIntent statusIntent = PendingIntent.getActivity(this, 0, mainIntent,	0);

		Intent closeIntent = new Intent(this, MainActivity.class);
		closeIntent.putExtra("close", true);
		PendingIntent closePendingIntent = PendingIntent.getActivity(this, 1, closeIntent, 0);

		Notification.Builder builder = new Notification.Builder(this)
				.setSmallIcon(R.mipmap.ic_status)
				.setContentTitle("PitDroid Monitor")
				.setContentText(contentText)
				.setOngoing(true)
				.setContentIntent(statusIntent)
				.addAction(R.mipmap.ic_status, "Close", closePendingIntent);

		startForeground(kStatusNotificationId, builder.build());
	}

	private void updateAlarmNotification(final NamedSample latestSample, final HeaterMeter heatermeter)
	{
		String contentText = "";

		boolean hasAlarms = false;

		if (latestSample != null)
		{
			// If we've got a sample, check if any of the alarms are triggered
			for (int p = 0; p < HeaterMeter.kNumProbes; p++)
			{
				String alarmText = heatermeter.formatAlarm(p, latestSample.mProbes[p]);
				if (alarmText.length() > 0)
				{
					hasAlarms = true;

					if (contentText.length() > 0)
					{
						contentText += "\n";
					}

					contentText += latestSample.mProbeNames[p] +" " + alarmText;
				}
			}
		}
		else
		{
			// If we didn't get a sample, that's an alarm in itself
			if (heatermeter.mAlarmOnLostConnection)
			{
				hasAlarms = true;
			}
			contentText = getText(R.string.no_server).toString();
		}

		if (hasAlarms)
		{
			if (BuildConfig.DEBUG)
			{
				Log.v(TAG, "Alarm notification:" + contentText);
			}

			Intent alarmIntent = new Intent(this, MainActivity.class);
			PendingIntent alarmPendingIntent = PendingIntent.getActivity(this, 0, alarmIntent, 0);

			Notification.Builder alarmBuilder = new Notification.Builder(this)
					.setSmallIcon(R.mipmap.ic_status)
					.setContentTitle("PitDroid Alarm")
					.setContentText(contentText)
					.setContentIntent(alarmPendingIntent)
					.setDefaults(Notification.DEFAULT_VIBRATE | Notification.DEFAULT_LIGHTS);

			AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
			boolean isSilentMode = (am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL);

			if (BuildConfig.DEBUG)
			{
				Log.v(TAG, "Silent mode:" + (isSilentMode ? "on" : "off"));
			}

			if (!isSilentMode || heatermeter.mAlwaysSoundAlarm)
			{
				if (BuildConfig.DEBUG)
				{
					Log.v(TAG, "Using alarm sound");
				}

				Uri alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
				alarmBuilder.setSound(alert, AudioManager.STREAM_ALARM);
			}
			else
			{
				if (BuildConfig.DEBUG)
				{
					Log.v(TAG, "Not using alarm sound");
				}
			}

			NotificationManager notificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);;

			// Build the notification and issues it with notification manager.
			notificationManager.notify(kAlarmNotificationId, alarmBuilder.build());
		}
	}

	@Override
	public IBinder onBind(Intent intent)
	{
		return null;
	}
}