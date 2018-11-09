package com.bonstead.pitdroid;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import android.content.SharedPreferences;
import android.util.Log;

import au.com.bytecode.opencsv.CSVReader;

public class HeaterMeter
{
	private final static String TAG = "HeaterMeter";

	final static int kNumProbes = 4;
	private final static String kHistoryURL = "/luci/lm/hist";
	private final static String kStatusURL = "/luci/lm/hmstatus";
	private final static String kAuthURL = "/luci/admin/lm";

	// No point in trying to sample faster than this, it's the update rate of the
	// HeaterMeter hardware
	final static int kMinSampleTime = 1000;
	// Wait at least this long between full history refreshes
	private final static long kMinHistoryUpdateTime = 5000;
	// If we're updating and it's been more than this long since the last update, force a
	// full history refresh, so we don't have too much data missing.
	private final static long kMaxUpdateDelta = 5000;

	// User settings
	private String[] mServerAddress = new String[2];
	private int mCurrentServer = 0;
	private String mAdminPassword;
	int mBackgroundUpdateTime;
	boolean mAlwaysSoundAlarm = true;
	boolean mAlarmOnLostConnection = true;
	int[] mProbeLoAlarm = new int[kNumProbes];
	int[] mProbeHiAlarm = new int[kNumProbes];
	boolean mKeepScreenOn;

	ArrayList<Sample> mSamples = new ArrayList<>();
	NamedSample mLatestSample;
	String[] mProbeNames = new String[kNumProbes];
	private double[] mDegreesPerHour = new double[kNumProbes];

	String mLastStatusMessage = null;

	private long mLastUpdateTime = 0;
	private long mLastHistoryTime = 0;
	private int mNewestTime = 0;
	private double mMinTemperature = Double.MAX_VALUE;
	private double mMaxTemperature = Double.MIN_VALUE;
	private ArrayList<Listener> mListeners = new ArrayList<>();
	private DecimalFormat mOneDec = new DecimalFormat("0.0");

	// For authentication, the cookie that's passed, and the URL token
	private String mAuthCookie;
	private String mAuthToken;

	private ArrayList<Sample> mSavedHistory;
	private String[] mSavedProbeNames = new String[kNumProbes];

	class Sample
	{
		int mTime;
		double mFanSpeed;
		double mLidOpen;
		double mSetPoint;
		double[] mProbes = new double[kNumProbes];

		Sample()
		{
			mTime = 0;
			mFanSpeed = 0;
			mLidOpen = 0;
			mSetPoint = Double.NaN;
			for (int p = 0; p < kNumProbes; p++)
				mProbes[p] = Double.NaN;
		}

		Sample(Sample otherSample)
		{
			mTime = otherSample.mTime;
			mFanSpeed = otherSample.mFanSpeed;
			mLidOpen = otherSample.mLidOpen;
			mSetPoint = otherSample.mSetPoint;
			System.arraycopy(otherSample.mProbes, 0, mProbes, 0, kNumProbes);
		}
	}

	class NamedSample extends Sample
	{
		String[] mProbeNames = new String[kNumProbes];
		double[] mDegreesPerHour = new double[kNumProbes];

		NamedSample()
		{
			super();

			for (int p = 0; p < kNumProbes; p++)
				mDegreesPerHour[p] = 0.0;
		}

		NamedSample(Sample otherSample)
		{
			super(otherSample);

			for (int p = 0; p < kNumProbes; p++)
				mDegreesPerHour[p] = 0.0;
		}
	}

	interface Listener
	{
		void samplesUpdated(final NamedSample latestSample);
	}

	public HeaterMeter()
	{
	}

	void addListener(Listener listener)
	{
		mListeners.add(listener);

		// To avoid waiting for the first refresh, grab the latest sample and send it over right away
		if (mLatestSample != null)
		{
			listener.samplesUpdated(mLatestSample);
		}
	}

	void removeListener(Listener listener)
	{
		mListeners.remove(listener);
	}

	double getNormalized(double temperature)
	{
		return (temperature - mMinTemperature) / (mMaxTemperature - mMinTemperature);
	}

	double getOriginal(double normalized)
	{
		return (normalized * (mMaxTemperature - mMinTemperature)) + mMinTemperature;
	}

	String formatTemperature(double temperature)
	{
		return mOneDec.format(temperature) + "°";
	}

	boolean hasAlarms()
	{
		for (int p = 0; p < kNumProbes; p++)
		{
			if (mProbeLoAlarm[p] > 0 || mProbeHiAlarm[p] > 0)
			{
				return true;
			}
		}

		return false;
	}

	// Returns the text to display for a triggered alarm, or an empty string if the alarm isn't triggered.
	String formatAlarm(int probeIndex, double temperature)
	{
		boolean hasLo = mProbeLoAlarm[probeIndex] > 0;
		boolean hasHi = mProbeHiAlarm[probeIndex] > 0;

		if ((hasLo || hasHi) && Double.isNaN(temperature))
		{
			return "off";
		}
		else if (hasLo && temperature < mProbeLoAlarm[probeIndex])
		{
			return formatTemperature(mProbeLoAlarm[probeIndex] - temperature) + " below alarm point";
		}
		else if ((hasHi && temperature > mProbeHiAlarm[probeIndex]))
		{
			return formatTemperature(temperature - mProbeHiAlarm[probeIndex]) + " above alarm point";
		}

		return "";
	}

	String getTemperatureChangeText(int probeIndex)
	{
		double degreesPerHour = mDegreesPerHour[probeIndex];

		// Don't display if there isn't clear increase, prevents wild numbers
		if (degreesPerHour < 1.0)
		{
			return null;
		}

		String timeStr = String.format(Locale.US, "%.1f°/hr", degreesPerHour);

		Sample lastSample = mSamples.get(mSamples.size() - 1);
		double currentTemp = lastSample.mProbes[probeIndex];

		// If we've got an alarm set and our most recent sample had a reading for this
		// probe, see if we can calculate an estimated time to alarm.
		if (mProbeHiAlarm[probeIndex] > 0 && !Double.isNaN(currentTemp))
		{
			int minutesRemaining = (int) (((mProbeHiAlarm[probeIndex] - currentTemp) / degreesPerHour) * 60);
			if (minutesRemaining > 0)
			{
				int hoursRemaining = minutesRemaining / 60;
				minutesRemaining = minutesRemaining % 60;

				timeStr += String.format(Locale.US, ", %d:%02d to %d°", hoursRemaining,
						minutesRemaining, mProbeHiAlarm[probeIndex]);
			}
		}

		return timeStr;
	}

	/*
	 * Return the minimum and maximum times from our samples
	 */
	int getMinTime()
	{
		if (mSamples.size() > 0)
			return mSamples.get(0).mTime;
		else
			return 0;
	}

	int getMaxTime()
	{
		if (mSamples.size() > 0)
			return mSamples.get(mSamples.size() - 1).mTime;
		else
			return 0;
	}

	private void updateMinMax(double temp)
	{
		// Round our min and max temperatures up/down to a multiple of 10 degrees, and
		// make sure they're increased/decreased by at least 1 degree. This gives us some
		// visual headroom in the graph.
		double roundedUp = Math.ceil((temp + 5.0) / 10.0) * 10.0;
		double roundedDown = Math.floor((temp - 5.0) / 10.0) * 10.0;

		mMinTemperature = Math.min(mMinTemperature, roundedDown);
		mMaxTemperature = Math.max(mMaxTemperature, roundedUp);
	}

	void initPreferences(SharedPreferences prefs)
	{
		mServerAddress[0] = prefs.getString("server", "");
		mServerAddress[1] = prefs.getString("altServer", "");

		for (int i = 0; i < 2; i++)
		{
			if (!mServerAddress[i].matches("^(https?)://.*$"))
			{
				mServerAddress[i] = "http://" + mServerAddress[i];
			}
		}

		mAdminPassword = prefs.getString("adminPassword", "");

		mBackgroundUpdateTime = Integer.valueOf(prefs.getString("backgroundUpdateTime", "15"));

		mAlwaysSoundAlarm = prefs.getBoolean("alwaysSoundAlarm", true);
		mAlarmOnLostConnection = prefs.getBoolean("alarmOnLostConnection", true);

		mKeepScreenOn = prefs.getBoolean("keepScreenOn", false);

		for (int p = 0; p < kNumProbes; p++)
		{
			String loName = "alarm" + p + "Lo";
			mProbeLoAlarm[p] = prefs.getInt(loName, -70);

			String hiName = "alarm" + p + "Hi";
			mProbeHiAlarm[p] = prefs.getInt(hiName, -200);
		}
	}

	void preferencesChanged(SharedPreferences prefs)
	{
		SharedPreferences.Editor editor = prefs.edit();

		for (int p = 0; p < kNumProbes; p++)
		{
			String loName = "alarm" + p + "Lo";
			editor.putInt(loName, mProbeLoAlarm[p]);

			String hiName = "alarm" + p + "Hi";
			editor.putInt(hiName, mProbeHiAlarm[p]);
		}

		editor.apply();
	}

	NamedSample getSample()
	{
		BufferedReader reader = getUrlReader(kStatusURL);
		if (reader != null)
		{
			return parseStatus(readerToString(reader));
		}

		return null;
	}

	Object updateThread()
	{
		Object ret = null;

		long currentTime = System.currentTimeMillis();

		long timeSinceLastUpdate = currentTime - mLastUpdateTime;

		// If we don't have any samples, or we have over 500, rebuild the samples from
		// scratch based on the history. The upper end check keeps us from blowing memory
		// on hours and hours of high precision samples that you won't even be able to
		// see.
		// The time check is so that we don't read the history, then immediately read it
		// again because our previous read hadn't been processed by the main thread yet.
		if ((mSamples.size() == 0 || mSamples.size() > 500 || timeSinceLastUpdate > kMaxUpdateDelta)
				&& (currentTime - mLastHistoryTime) > kMinHistoryUpdateTime)
		{
			mLastHistoryTime = currentTime;

			if (BuildConfig.DEBUG)
			{
				Log.v(TAG, "Getting history");
			}

			if (mSavedHistory != null)
			{
				ret = mSavedHistory;
			}
			else
			{
				BufferedReader reader = getUrlReader(kHistoryURL);
				if (reader != null)
				{
					ret = parseHistory(reader);
				}
			}
		}
		else
		{
			if (mSavedHistory != null)
			{
				NamedSample namedSample = new NamedSample(mSavedHistory.get(mSavedHistory.size() - 1));
				namedSample.mProbeNames = mSavedProbeNames;
				ret = namedSample;
			}
			else
			{
				BufferedReader reader = getUrlReader(kStatusURL);
				if (reader != null)
				{
					ret = parseStatus(readerToString(reader));
				}
			}
		}

		if (ret != null)
		{
			mLastUpdateTime = currentTime;

			// We got a valid result, so we assume we connected to the server ok. If we've
			// got a password and we haven't successfully authenticated yet, give it a
			// try.
			if (mAdminPassword != null && mAdminPassword.length() > 0 && !isAuthenticated())
			{
				authenticate();
			}
		}

		return ret;
	}

	// Suppress warning since we know it's an ArrayList of samples, even if Java doesn't
	@SuppressWarnings("unchecked")
	void updateMain(Object data)
	{
		mLatestSample = null;

		if (data instanceof NamedSample)
		{
			mLatestSample = addStatus((NamedSample) data);
		}
		else if (data != null)
		{
			mLatestSample = addHistory((ArrayList<Sample>) data);
		}

		for (int l = 0; l < mListeners.size(); l++)
			mListeners.get(l).samplesUpdated(mLatestSample);
	}

	private NamedSample parseStatus(String status)
	{
		try
		{
			JSONTokener tokener = new JSONTokener(status);
			JSONObject json = new JSONObject(tokener);

			NamedSample sample = new NamedSample();

			sample.mTime = json.getInt("time");
			sample.mSetPoint = json.getDouble("set");

			JSONObject fanInfo = json.getJSONObject("fan");
			sample.mFanSpeed = fanInfo.getDouble("c");

			sample.mLidOpen = json.getDouble("lid");

			JSONArray temps = json.getJSONArray("temps");
			for (int i = 0; i < temps.length(); i++)
			{
				JSONObject row = temps.getJSONObject(i);

				sample.mProbeNames[i] = row.getString("n");

				if (!row.isNull("c"))
				{
					sample.mProbes[i] = row.getDouble("c");
				}
				else
				{
					sample.mProbes[i] = Double.NaN;
				}

				if (!row.isNull("dph"))
				{
					sample.mDegreesPerHour[i] = row.getDouble("dph");
				}
				else
				{
					sample.mDegreesPerHour[i] = 0.0;
				}
			}

			return sample;
		}
		catch (JSONException e)
		{
			e.printStackTrace();
			return null;
		}
	}

	private NamedSample addStatus(NamedSample sample)
	{
		if (mNewestTime != sample.mTime)
		{
			mNewestTime = sample.mTime;

			for (int p = 0; p < kNumProbes; p++)
			{
				mProbeNames[p] = sample.mProbeNames[p];
				mDegreesPerHour[p] = sample.mDegreesPerHour[p];
			}

			updateMinMax(sample.mSetPoint);

			for (int p = 0; p < kNumProbes; p++)
			{
				if (!Double.isNaN(sample.mProbes[p]))
				{
					updateMinMax(sample.mProbes[p]);
				}
			}

			Sample simpleSample = new Sample(sample);

			mSamples.add(simpleSample);
		}

		return sample;
	}

	void setHistory(Reader reader)
	{
		BufferedReader br = new BufferedReader(reader);
		try
		{
			String line = null;
			for (int i = 0; i < kNumProbes; i++)
			{
				mSavedProbeNames[i] = br.readLine();
			}

			mSavedHistory = parseHistory(br);
		}
		catch (IOException e)
		{
			if (BuildConfig.DEBUG)
			{
				Log.e(TAG, "IO exception", e);
			}
		}
	}

	private ArrayList<Sample> parseHistory(Reader reader)
	{
		try
		{
			ArrayList<Sample> history = new ArrayList<>();

			CSVReader csvReader = new CSVReader(reader);

			String[] nextLine;
			while ((nextLine = csvReader.readNext()) != null)
			{
				// Without a valid set point the graph doesn't work
				if (!Double.isNaN((parseDouble(nextLine[1]))))
				{
					Sample sample = new Sample();

					// First parameter is the time
					sample.mTime = Integer.parseInt(nextLine[0]);

					// Second is the set point
					sample.mSetPoint = parseDouble(nextLine[1]);

					// Third through sixth are the probe temps
					for (int i = 0; i < kNumProbes; i++)
					{
						sample.mProbes[i] = parseDouble(nextLine[i + 2]);
					}

					// Seventh is the fan speed/lid open
					sample.mFanSpeed = parseDouble(nextLine[6]);
					if (sample.mFanSpeed < 0)
					{
						sample.mLidOpen = 1.0;
						sample.mFanSpeed = 0.0;
					}
					else
					{
						sample.mLidOpen = 0.0;
					}

					history.add(sample);
				}
			}

			return history;
		}
		catch (IOException e)
		{
			if (BuildConfig.DEBUG)
			{
				Log.e(TAG, "IO exception", e);
			}
			return null;
		}
	}

	private NamedSample addHistory(ArrayList<Sample> history)
	{
		mSamples = history;

		mMinTemperature = Double.MAX_VALUE;
		mMaxTemperature = Double.MIN_VALUE;

		for (Sample sample : mSamples)
		{
			mNewestTime = Math.max(mNewestTime, sample.mTime);

			updateMinMax(sample.mSetPoint);

			for (int p = 0; p < kNumProbes; p++)
			{
				if (!Double.isNaN(sample.mProbes[p]))
				{
					updateMinMax(sample.mProbes[p]);
				}
			}
		}

		NamedSample latestSample = null;
		if (mSamples.size() > 0)
		{
			latestSample = new NamedSample(mSamples.get(mSamples.size() - 1));
			System.arraycopy(mProbeNames, 0, latestSample.mProbeNames, 0, kNumProbes);
		}

		return latestSample;
	}

	private BufferedReader getUrlReader(String urlName)
	{
		int currentServer = mCurrentServer;

		for (int i = 0; i < 2; i++)
		{
			try
			{
				URL url = new URL(mServerAddress[currentServer] + urlName);

				URLConnection connection = url.openConnection();

				// Set a 5 second timeout, otherwise we can end up waiting minutes for an
				// unreachable server to resolve.
				connection.setConnectTimeout(5000);
				connection.setReadTimeout(5000);

				BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));

				// If we made it here then the connection must have succeeded, so make sure the
				// current server matches the one we used
				if (mCurrentServer != currentServer)
				{
					mCurrentServer = currentServer;
				}

				return reader;
			}
			catch (MalformedURLException e)
			{
				if (BuildConfig.DEBUG)
				{
					Log.e(TAG, "Bad server address");
				}
			}
			catch (UnknownHostException e)
			{
				if (BuildConfig.DEBUG)
				{
					Log.e(TAG, "Unknown host: " + e.getLocalizedMessage());
				}
			}
			catch (IOException e)
			{
				if (BuildConfig.DEBUG)
				{
					Log.e(TAG, "IO exception" + e.getLocalizedMessage());
				}
			}
			catch (IllegalArgumentException e)
			{
				if (BuildConfig.DEBUG)
				{
					Log.e(TAG, "Argument exception (probably bad port)");
				}
			}

			currentServer = (currentServer + 1) % 2;

			if (BuildConfig.DEBUG)
			{
				Log.e(TAG, "Connection failed, switching to server " + currentServer);
			}
		}

		return null;
	}

	private Double parseDouble(String value)
	{
		try
		{
			return Double.parseDouble(value);
		}
		catch (NumberFormatException e)
		{
			return Double.NaN;
		}
	}

	private String readerToString(BufferedReader reader)
	{
		try
		{
			StringBuilder builder = new StringBuilder();
			for (String line = null; (line = reader.readLine()) != null; )
			{
				builder.append(line).append("\n");

				// http://code.google.com/p/android/issues/detail?id=14562
				// For Android 2.x, reader gets closed and throws an exception
				if (!reader.ready())
				{
					break;
				}
			}

			return builder.toString();
		}
		catch (IOException e)
		{
			e.printStackTrace();
			return null;
		}
	}

	public void changePitSetTemp(int newTemp)
	{
		if (!isAuthenticated())
			return;

		String setAddr = mServerAddress[mCurrentServer] + "/luci";
		if (mAuthToken != null)
			setAddr += "/;stok=" + mAuthToken;
		setAddr += "/admin/lm/set?sp=" + newTemp;

		HttpURLConnection urlConnection = null;

		try
		{
			URL url = new URL(setAddr);
			urlConnection = (HttpURLConnection) url.openConnection();
			urlConnection.setDoOutput(true);
			urlConnection.setRequestMethod("POST");
			urlConnection.setRequestProperty("Cookie", "sysauth=" + mAuthCookie);

			urlConnection.getInputStream();
		}
		catch (MalformedURLException e)
		{
			if (BuildConfig.DEBUG)
			{
				Log.e(TAG, "Bad server address");
			}
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}

		if (urlConnection != null)
		{
			urlConnection.disconnect();
		}
	}

	private boolean isAuthenticated()
	{
		return mAuthCookie != null;
	}

	private void authenticate()
	{
		if (BuildConfig.DEBUG)
		{
			Log.v(TAG, "Attempting authentication");
		}

		HttpURLConnection urlConnection = null;

		try
		{
			URL url = new URL(mServerAddress[mCurrentServer] + kAuthURL);
			urlConnection = (HttpURLConnection) url.openConnection();
			urlConnection.setDoOutput(true);

			urlConnection.setDoInput(true);
			urlConnection.setRequestMethod("POST");
			urlConnection.setInstanceFollowRedirects(false);

			DataOutputStream out = new DataOutputStream(urlConnection.getOutputStream());
			out.writeBytes("username=root&");
			out.writeBytes("password=" + URLEncoder.encode(mAdminPassword, "UTF-8"));
			out.flush();
			out.close();

			String cookieHeader = urlConnection.getHeaderField("Set-Cookie");

			// The cookieHeader will be null if we used the wrong password
			if (cookieHeader != null)
			{
				String[] cookies = cookieHeader.split(";");

				for (String cookie : cookies)
				{
					String[] cookieChunks = cookie.split("=");
					String cookieKey = cookieChunks[0];
					if (cookieKey.equals("sysauth"))
					{
						mAuthCookie = cookieChunks[1];
					}
					else if (cookieKey.equals("stok"))
					{
						mAuthToken = cookieChunks[1];
					}
				}
			}
			else
			{
				// If we fail to authenticate null out the password, so we won't keep
				// trying. It'll automatically get filled in again if the user changes it
				// in the settings.
				mAdminPassword = null;
			}
		}
		catch (Exception e)
		{
			if (BuildConfig.DEBUG)
			{
				e.printStackTrace();
			}
		}

		if (urlConnection != null)
		{
			urlConnection.disconnect();
		}

		if (isAuthenticated())
		{
			mLastStatusMessage = "Authentication succeeded";
		}
		else
		{
			mLastStatusMessage = "Authentication failed";
		}
	}
}
