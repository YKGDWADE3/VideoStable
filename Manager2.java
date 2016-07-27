package com.arcsoft.videostabilizer.manager;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.Message;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.Size;
import android.view.GestureDetector;
import android.view.GestureDetector.OnGestureListener;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.arcsoft.videostabilizer.R;
import com.arcsoft.videostabilizer.config.ConfigMgr;
import com.arcsoft.videostabilizer.define.AppGlobalDef;
import com.arcsoft.videostabilizer.define.CameraState;
import com.arcsoft.videostabilizer.define.IPreviewCallbackEx;
import com.arcsoft.videostabilizer.define.MSize;
import com.arcsoft.videostabilizer.define.NotifyListener;
import com.arcsoft.videostabilizer.define.OnConfigChangedListener;
import com.arcsoft.videostabilizer.define.UiCmdListener;
import com.arcsoft.videostabilizer.engine2.CamEngine2;
import com.arcsoft.videostabilizer.jni.JNI;
import com.arcsoft.videostabilizer.manager.OpenPageController.PageListener;
import com.arcsoft.videostabilizer.opengl.OpenGLView;
import com.arcsoft.videostabilizer.systemmgr.ConfigManager;
import com.arcsoft.videostabilizer.systemmgr.MediaManager;
import com.arcsoft.videostabilizer.systemmgr.TaskQueueThread;
import com.arcsoft.videostabilizer.ui.ComparePage;
import com.arcsoft.videostabilizer.ui.HelperView;
import com.arcsoft.videostabilizer.ui.InfoView;
import com.arcsoft.videostabilizer.ui.PreviewRightBar;
import com.arcsoft.videostabilizer.ui.RecordTimeBar;
import com.arcsoft.videostabilizer.utils.DateUtil;
import com.arcsoft.videostabilizer.utils.FileUtil;
import com.arcsoft.videostabilizer.utils.LogUtil;
import com.arcsoft.videostabilizer.utils.MSizeUtil;
import com.arcsoft.videostabilizer.utils.MathUtil;
import com.arcsoft.videostabilizer.utils.ResourceUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class Manager2 extends RelativeLayout implements OnGestureListener, IPreviewCallbackEx, UiCmdListener, NotifyListener, OnConfigChangedListener, Callback, PageListener {

	private final String TAG = getClass().getSimpleName();
	public static final int WRITE_DONE = 123;

	private Context mContext = null;
	private ConfigMgr mConfigMgr = null;
	private Handler mHandler = new Handler(this);

	private CamEngine2 mCamEngine = null;

	private OpenGLView mOpenGLView = null;
	private PreviewRightBar mPreviewRightBar = null;
	private TextView mInfoView = null;
	private TextView mRecordTimeBar = null;

	private OpenPageController mOpenPageController;
	private HelperView mHelperView = null;
	private Button mBtnDump;

	private String mVideoFileName = null;
	private String mVideoTimeStampFileName = null;
	private String mSensorGyroscopeFileName = null;
	private String mSensorRotationVectorFileName = null;
	private String mSensorAccelerometerFileName = null;
	private String mSensorMagneticFiledFileName = null;

	private Thread writeRecordTimeStampFile = null;
	private Thread writeAccelerometerFile = null;
	private Thread writeGyroscopeFile = null;
	private Thread writeMagneticFieldFile = null;
	private Thread writeRotationVectorFile = null;

	private long mRecordingStartTime;
	
	private SensorManager mSensorManager = null;
	private Sensor mSensor1 = null;
	private Sensor mSensor2 = null;
	private float[] mSensorArray = new float[3];
	private long mSensorTimeStamp = 0;
	private boolean mIsFrontCamera = false;
	private long mSystemSleepTime = 0;

	private String strDate = null;
	public Manager2(Context context) {
		super(context);
		mContext = context;
	}

	public void onCreate() {
		LogUtil.LogD(TAG, "onCreate   <---");

		// load config
		mConfigMgr = new ConfigMgr(mContext, this);
		mConfigMgr.loadSerializedConfig();

		// scan media files
		MediaManager.getInstance(mContext).prepareMediaScanning();

		// create ui
		createUI();

		// show then hide compare page when launch
		mHandler.sendEmptyMessageDelayed(AppGlobalDef.MessageIds.MSG_SHOW_COMPARE_PAGE, 1000);
		mHandler.sendEmptyMessageDelayed(AppGlobalDef.MessageIds.MSG_CLOSE_COMPARE_PAGE, 3000);
		
		//register sensor
		mSensorManager= (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);   
		
		mSystemSleepTime = SystemClock.elapsedRealtimeNanos() - SystemClock.uptimeMillis() * 1000 * 1000;
		LogUtil.LogD(TAG, "####mSystemSleepTime = " + mSystemSleepTime);
		
		//List<Sensor> list = mSensorManager.getSensorList(Sensor.TYPE_ALL);

		LogUtil.LogD(TAG, "onCreate   --->");
	}

	private void createUI() {
		// background color
		setBackgroundColor(ResourceUtil.getColor(mContext, R.color.bg_preview));

		int debugMode = (Integer) mConfigMgr.getConfig(ConfigMgr.KEY_CONFIG_DEBUG_MODE);
		int compareMode = (Integer) mConfigMgr.getConfig(ConfigMgr.KEY_CONFIG_COMPARE_MODE);

		// GLSurface View
		mOpenGLView = new OpenGLView(mContext, new GestureDetector(mContext, this), compareMode, debugMode);
		MSize curSize = (MSize) mConfigMgr.getConfig(ConfigMgr.KEY_CONFIG_PREVIEW_SIZE);
		if (null != curSize && !curSize.equals(MSizeUtil.IntToMSize(ConfigMgr.UNKNOWN_VALUE))) {
			LogUtil.LogD(TAG, "get preview size from config file, so set surface size when create ui");
			MSize size = MathUtil.getSurfaceSize(curSize.width, curSize.height, true);
			LayoutParams param = new LayoutParams(size.width, size.height);
			param.addRule(RelativeLayout.CENTER_IN_PARENT);
			addView(mOpenGLView, param);
		} else {
			LogUtil.LogD(TAG, "cannot get preview size from config file, so set surface size full screen when create ui");
			LayoutParams param = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
			param.addRule(RelativeLayout.CENTER_IN_PARENT);
			addView(mOpenGLView, param);
		}

		// add ArcSoft LOGO
		ImageView imgLogo = new ImageView(mContext);
		imgLogo.setImageResource(R.drawable.app_logo);
		LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
		params.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
		params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
		addView(imgLogo, params);

		// right bar
		mPreviewRightBar = new PreviewRightBar(mContext, this, mConfigMgr);
		mPreviewRightBar.setVisibility(AppGlobalDef.SHOW_RIGHT_BAR ? View.VISIBLE : View.GONE);
		params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
		params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
		params.rightMargin = ResourceUtil.getPixValueFromDimenXML(mContext, R.dimen.rightbar_margin_right);
		addView(mPreviewRightBar, params);

		// info view
		mInfoView = new InfoView(mContext);
		params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
		params.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
		params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
		params.topMargin = ResourceUtil.getPixValueFromDimenXML(mContext, R.dimen.info_view_margin_top);
		params.leftMargin = ResourceUtil.getPixValueFromDimenXML(mContext, R.dimen.info_view_info_margin_left);
		addView(mInfoView, params);

		// record time bar
		mRecordTimeBar = new RecordTimeBar(mContext);
		params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
		params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
		params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
		params.topMargin = ResourceUtil.getPixValueFromDimenXML(mContext, R.dimen.record_time_margin_top);
		params.rightMargin = ResourceUtil.getPixValueFromDimenXML(mContext, R.dimen.rightbar_margin_right);
		addView(mRecordTimeBar, params);

		// HelperView
		mHelperView = new HelperView(mContext);
		params = new RelativeLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
		params.addRule(RelativeLayout.CENTER_IN_PARENT);
		mHelperView.setVisibility(INVISIBLE);
		addView(mHelperView, params);
		
		// dump button
		mBtnDump = new Button(mContext);
		mBtnDump.setText(R.string.ids_dump_start);
		params = new RelativeLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
		params.addRule(RelativeLayout.CENTER_VERTICAL | RelativeLayout.ALIGN_PARENT_LEFT);
		mBtnDump.setVisibility(View.VISIBLE);
		addView(mBtnDump, params);
		mBtnDump.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				if (mbDumpEnabled) {
					enabledDump(false);
				} else {
					enabledDump(true);
				}
			}
		});

		// open page controller
		mOpenPageController = new OpenPageController(mContext, mConfigMgr, mHandler, this, this, this);

		// debug mode
		setDebugMode(debugMode);

		// compare mode
		setCompareMode(compareMode);
	}

	private void setDebugMode(int mode) {
		LogUtil.LogD(TAG, "setDebugMode   <---");
		LogUtil.LogD(TAG, "setDebugMode   mode = " + mode);

//		boolean logEnabled = mode == ConfigMgr.VALUE_DEBUG_MODE_DEBUG;
//		JNI.native_setLogEnabled(logEnabled);
//		LogUtil.setEnabled(logEnabled);

		if (null != mOpenGLView) {
			LogUtil.LogD(TAG, "setDebugMode   OpenGLView setDebugMode");
			mOpenGLView.setDebugMode(mode);
		}

		boolean infoEnable = mode != ConfigMgr.VALUE_DEBUG_MODE_OFF;
		if (null != mInfoView) {
			mInfoView.setVisibility(infoEnable ? VISIBLE : GONE);
		}

		if (infoEnable) {
			mHandler.sendEmptyMessage(AppGlobalDef.MessageIds.MSG_UPDATE_PERFORMANCE_INFO);
		} else {
			mHandler.removeMessages(AppGlobalDef.MessageIds.MSG_UPDATE_PERFORMANCE_INFO);
		}

		resetPerformanceInfo();

		LogUtil.LogD(TAG, "setDebugMode   --->");
	}

	private void setCompareMode(int compareMode) {
		LogUtil.LogD(TAG, "setCompareMode   <---");
		LogUtil.LogD(TAG, "setCompareMode   mode = " + compareMode);

		JNI.native_setCompareMode(compareMode);
		if (null != mOpenGLView) {
			mOpenGLView.setCompareMode(compareMode);
		}
		resetPerformanceInfo();

		LogUtil.LogD(TAG, "setCompareMode   --->");
	}

	final int mSensorType = ConfigManager.getInstance().getSensorType();

	public void onResume() {
		LogUtil.LogD(TAG, "onResume   <---");

		initCurrentCamera();
		//initWriteThread();
		
		LogUtil.LogD(TAG, "onResume   --->");
	}
	
	private void initCurrentCamera() {
		try {
			// open camera
			mCamEngine = new CamEngine2(mContext, this);
			mCamEngine.startBackgroundThread();
			
	        CameraManager manager = (CameraManager)mContext.getSystemService(Context.CAMERA_SERVICE);
	        
	        for (String cameraId : manager.getCameraIdList()) { 
				 CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

		        if(mIsFrontCamera){
		            if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
			            continue;
			        }
		        }
		        else{
			        if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
			            continue;
			        }
		        }
		
		        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
		        
				List<Size> supportedPictureSize = new ArrayList<Size>();
				List<Size> supportedPreviewSize = new ArrayList<Size>();
		        // For still image captures, we use the largest available size.
				supportedPictureSize = Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888));
				Size pictureSize = Collections.max(supportedPictureSize, new CompareSizesByArea());
		
				supportedPreviewSize = Arrays.asList(map.getOutputSizes(SurfaceTexture.class));
				
				// set supported preview sizes
				final MSize maxPreviewSize = new MSize(1920, 1080);
				if (null == mConfigMgr.getSupportedResolutions(ConfigMgr.KEY_CONFIG_PREVIEW_SIZE)) {
					MSize[] sizeArr = MSizeUtil.mapCamera2SizeList2MSizeArray(supportedPreviewSize);
					sizeArr = MSizeUtil.filterResolutions(sizeArr, maxPreviewSize);
					mConfigMgr.setSupportedResolutions(ConfigMgr.KEY_CONFIG_PREVIEW_SIZE, sizeArr);
				}
		
				// set default preview size
				final MSize preferredPreviewSize = new MSize(1920, 1080);
				MSize defSize = (MSize) mConfigMgr.getDefaultValue(ConfigMgr.KEY_CONFIG_PREVIEW_SIZE);
				if (defSize.equals(MSizeUtil.IntToMSize(ConfigMgr.UNKNOWN_VALUE))) {
					MSize[] sizeArr = mConfigMgr.getSupportedResolutions(ConfigMgr.KEY_CONFIG_PREVIEW_SIZE);
					defSize = MSizeUtil.getPreferredResolution(sizeArr, preferredPreviewSize);
					mConfigMgr.setDefaultValue(ConfigMgr.KEY_CONFIG_PREVIEW_SIZE, defSize);
				}
		
				// set preview size
				MSize curSize = (MSize) mConfigMgr.getConfig(ConfigMgr.KEY_CONFIG_PREVIEW_SIZE);
				if (curSize.equals(MSizeUtil.IntToMSize(ConfigMgr.UNKNOWN_VALUE))) {
					mConfigMgr.setConfig(ConfigMgr.KEY_CONFIG_PREVIEW_SIZE, defSize, false);
					curSize = defSize;
				}
				
				mCamEngine.openCamera(cameraId, new Size(curSize.width, curSize.height));
				mCamEngine.setPictureSize(pictureSize);
			
				// jni and gl surface view
				float trimRatio = ConfigManager.getInstance().getTrimRatio();
				LogUtil.LogD(TAG, "onResume, trimRatio: " + trimRatio);
		
				JNI.native_init(curSize.width, curSize.height, trimRatio);
				JNI.native_resetVS();
				
				//int degrees = CameraUtil.getDisplayOrientation((Activity) mContext, cameraId);
				//mOpenGLView.setDegrees(degrees);
				
				mOpenGLView.onResume();
		
				// set surface size
				setSurfaceSize(curSize.width, curSize.height);
		
				mCamEngine.setPreviewCallback(this);
		
				// update performance info
				int debugMode = (Integer) mConfigMgr.getConfig(ConfigMgr.KEY_CONFIG_DEBUG_MODE);
				boolean infoEnable = debugMode != ConfigMgr.VALUE_DEBUG_MODE_OFF;
				if (infoEnable) {
					if (null != mHandler) {
						mHandler.sendEmptyMessage(AppGlobalDef.MessageIds.MSG_UPDATE_PERFORMANCE_INFO);
					}
				}
		
				int freq = 1000 / (Integer) mConfigMgr.getConfig(ConfigMgr.KEY_CONFIG_SENSOR_FREQUENCY);

				if (mSensorType == AppGlobalDef.CONFIG_SENSOR_OFF) {
		
				} else if (mSensorType == AppGlobalDef.CONFIG_SENSOR_GYROSCOPE) {
					mSensor1 = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
					mSensorManager.registerListener(mSensorListener, mSensor1,freq * 1000);
					mSensor2 = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
					mSensorManager.registerListener(mSensorListener, mSensor2, freq * 1000);
				} else if (mSensorType == AppGlobalDef.CONFIG_SENSOR_ACCELEROMETER_MAGNETIC) {
					mSensor1 = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
					mSensor2 = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
					mSensorManager.registerListener(mSensorListener, mSensor1, freq * 1000);
					mSensorManager.registerListener(mSensorListener, mSensor2, freq * 1000);
				}
	        }
		} catch (Exception e) {
			// TODO: handle exception
		}
	}

	class writeTimeStamp implements Runnable{

		@Override
		public void run() {
			try {

				mPrintWriter = new PrintWriter(new FileOutputStream(new File(mVideoTimeStampFileName)));
				mPrintWriter.write(mRecordingFrameTimeStampBuffer.toString());
				mPrintWriter.close();
				mPrintWriter = new PrintWriter(new FileOutputStream(new File(mSensorAccelerometerFileName)));
				mPrintWriter.write(mAccelerometerBuffer.toString());
				mPrintWriter.close();
				mPrintWriter = new PrintWriter(new FileOutputStream(new File(mSensorGyroscopeFileName)));
				mPrintWriter.write(mGyroscopeBuffer.toString());
				mPrintWriter.close();
				mPrintWriter = new PrintWriter(new FileOutputStream(new File(mSensorMagneticFiledFileName)));
				mPrintWriter.write(mMagneticFieldBuffer.toString());
				mPrintWriter.close();
				mPrintWriter = new PrintWriter(new FileOutputStream(new File(mSensorRotationVectorFileName)));
				mPrintWriter.write(mRotationVectorBuffer.toString());
				mPrintWriter.close();
				mHandler.sendEmptyMessage(WRITE_DONE);
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			}

		}
	}
	/*private void initWriteThread(){
		writeRecordTimeStampFile = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					mPrintWriter = new PrintWriter(new FileOutputStream(new File(mVideoTimeStampFileName)));
					mPrintWriter.write(mRecordingFrameTimeStampBuffer.toString());
					mPrintWriter.close();


				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}
			}
		});
		writeAccelerometerFile = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					mPrintWriter = new PrintWriter(new FileOutputStream(new File(mSensorAccelerometerFileName)));
					mPrintWriter.write(mAccelerometerBuffer.toString());
					mPrintWriter.close();

				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}
			}
		});
		writeGyroscopeFile = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					mPrintWriter = new PrintWriter(new FileOutputStream(new File(mSensorGyroscopeFileName)));
					mPrintWriter.write(mGyroscopeBuffer.toString());
					mPrintWriter.close();

				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}
			}
		});
		writeMagneticFieldFile = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					mPrintWriter = new PrintWriter(new FileOutputStream(new File(mSensorMagneticFiledFileName)));
					mPrintWriter.write(mMagneticFieldBuffer.toString());
					mPrintWriter.close();

				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}
			}
		});

		writeRotationVectorFile = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					mPrintWriter = new PrintWriter(new FileOutputStream(new File(mSensorRotationVectorFileName)));
					mPrintWriter.write(mRotationVectorBuffer.toString());
					mPrintWriter.close();

				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}
			}
		});
	}*/
	
	public void onPause() {
		LogUtil.LogD(TAG, "onPause   <---");

		uninitCurrentCamera();

		LogUtil.LogD(TAG, "onPause   --->");
	}
	
	private void uninitCurrentCamera() {
		if (null != mHandler) {
			mHandler.removeMessages(AppGlobalDef.MessageIds.MSG_UPDATE_PERFORMANCE_INFO);
		}

		if (null != mOpenPageController) {
			mOpenPageController.closePage(false);
		}

		stopRecord();
		mCamEngine.setPreviewCallback(null);
		mCamEngine.stopPreview();
		mCamEngine.closeCamera();
		mCamEngine.stopBackgroundThread();

		mOpenGLView.onPause();
		if (mSensorType == AppGlobalDef.CONFIG_SENSOR_OFF) {

		} else if (mSensorType == AppGlobalDef.CONFIG_SENSOR_GYROSCOPE) {
			mSensorManager.unregisterListener(mSensorListener, mSensor1);
			mSensorManager.unregisterListener(mSensorListener, mSensor2);
		} else if (mSensorType == AppGlobalDef.CONFIG_SENSOR_ACCELEROMETER_MAGNETIC) {
			mSensorManager.unregisterListener(mSensorListener, mSensor1);
			mSensorManager.unregisterListener(mSensorListener, mSensor2);
		}
		
		JNI.native_release();
		
		if (null != mDumpThread) {
			mDumpThread.stop(true);
			mDumpThread = null;
		}
	}
	
	public void onDestroy() {
		LogUtil.LogD(TAG, "onDestroy   <---");

		// save config
		mConfigMgr.serialize();

		LogUtil.LogD(TAG, "onDestroy   --->");
	}
		
	private void switchCamera() {
		uninitCurrentCamera();
		mIsFrontCamera = !mIsFrontCamera;
		initCurrentCamera();
	}
	
	private void setSurfaceSize(int previewWidth, int previewHeight) {
		LogUtil.LogD(TAG, "setSurfaceSize   <---");
		LogUtil.LogD(TAG, "setSurfaceSize   previewWidth = " + previewWidth + ", previewHeight = " + previewHeight);

		if (null == mOpenGLView)
			return;

		MSize size = MathUtil.getSurfaceSize(previewWidth, previewHeight, false);
		RelativeLayout.LayoutParams param = (RelativeLayout.LayoutParams) mOpenGLView.getLayoutParams();
		if (param.width != size.width || param.height != size.height) {
			param.width = size.width;
			param.height = size.height;
			param.leftMargin = param.rightMargin = (AppGlobalDef.SCREEN_SIZE.width - size.width) / 2;
			param.topMargin = param.bottomMargin = (AppGlobalDef.SCREEN_SIZE.height - size.height) / 2;
			mOpenGLView.setLayoutParams(param);
		}

		LogUtil.LogD(TAG, "setSurfaceSize   --->");
	}
	
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }
	private boolean isRecording = false;
	private PrintWriter mPrintWriter = null;

	private void startRecord() {
		LogUtil.LogD(TAG, "startRecord   <---");
		strDate = DateFormat.format("yyyyMMddkkmmss", System.currentTimeMillis()).toString();
		File destDir = new File(AppGlobalDef.VIDEO_SAVE_PATH+"/"+strDate);
		if (!destDir.exists()) {
			destDir.mkdirs();
		}
		createFiles();
		mCamEngine.startRecord(mVideoFileName);
		LogUtil.LogD(TAG, "startRecord   --->");
	}

	private void createFiles(){
		mVideoFileName = FileUtil.makeFileName(AppGlobalDef.VIDEO_SAVE_PATH+"/"+strDate, AppGlobalDef.VIDEO_PREFIX, AppGlobalDef.VIDEO_POSTFIX);
		mVideoTimeStampFileName = FileUtil.makeFileName(AppGlobalDef.VIDEO_SAVE_PATH + "/" + strDate,
				"VideoTimeStamp" + AppGlobalDef.TXT_POSTFIX);
		mSensorAccelerometerFileName = FileUtil.makeFileName(AppGlobalDef.VIDEO_SAVE_PATH+"/"+strDate,
				"Accelerometer"+AppGlobalDef.TXT_POSTFIX);
		mSensorGyroscopeFileName = FileUtil.makeFileName(AppGlobalDef.VIDEO_SAVE_PATH+"/"+strDate,
				"Gyroscope"+AppGlobalDef.TXT_POSTFIX);
		mSensorMagneticFiledFileName = FileUtil.makeFileName(AppGlobalDef.VIDEO_SAVE_PATH+"/"+strDate,
				"MagneticFiled"+AppGlobalDef.TXT_POSTFIX);
		mSensorRotationVectorFileName = FileUtil.makeFileName(AppGlobalDef.VIDEO_SAVE_PATH+"/"+strDate,
				"RotationVector"+AppGlobalDef.TXT_POSTFIX);
		FileUtil.createEmptyFile(mVideoFileName);
		FileUtil.createEmptyFile(mSensorAccelerometerFileName);
		FileUtil.createEmptyFile(mSensorGyroscopeFileName);
		FileUtil.createEmptyFile(mSensorMagneticFiledFileName);
		FileUtil.createEmptyFile(mSensorRotationVectorFileName);
	}

	@Override
	public void startDump() {
		refreshBuffer();
		isRecording=true;
	}

	private void refreshBuffer(){
		mAccelerometerBuffer.delete(0,mAccelerometerBuffer.length());
		mMagneticFieldBuffer.delete(0,mMagneticFieldBuffer.length());
		mRotationVectorBuffer.delete(0,mRotationVectorBuffer.length());
		mGyroscopeBuffer.delete(0,mGyroscopeBuffer.length());
		mRecordingFrameTimeStampBuffer.delete(0,mRecordingFrameTimeStampBuffer.length());
	}

	private void stopRecord() {
		LogUtil.LogD(TAG, "stopRecord   <---");

		mCamEngine.stopRecord();

	}



	@Override
	public void endDump() {
		isRecording = false;
		LogUtil.LogD(TAG, "stopRecord   --->");
		LogUtil.LogE(TAG + "1", mAccelerometerBuffer.toString());
		LogUtil.LogE(TAG + "2", mGyroscopeBuffer.toString());
		LogUtil.LogE(TAG + "3", mRotationVectorBuffer.toString());
		LogUtil.LogE(TAG + "4", mMagneticFieldBuffer.toString());
		LogUtil.LogE(TAG + "5", mRecordingFrameTimeStampBuffer.toString());
		new Thread(new writeTimeStamp()).start();
	}

	private void onStartRecord() {
		LogUtil.LogD(TAG, "onStartRecord   <---");

		mOpenPageController.closePage(false);
		mRecordTimeBar.setVisibility(View.VISIBLE);
		mPreviewRightBar.onRecord(true);
		mRecordingStartTime = SystemClock.uptimeMillis();
		updateRecordingTime();

		LogUtil.LogD(TAG, "onStartRecord   --->");
	}

	private void onStopRecord() {
		LogUtil.LogD(TAG, "onStopRecord   <---");

		mHandler.removeMessages(AppGlobalDef.MessageIds.MSG_UPDATE_RECORDING_TIME);
		mRecordTimeBar.setVisibility(View.GONE);
		mRecordTimeBar.setText(AppGlobalDef.DEFAULT_RECORD_TIME_TEXT);
		mPreviewRightBar.onRecord(false);

		Size size = mCamEngine.getPreviewSize();
		MediaManager.getInstance(mContext).addMediaFileByFullPath(mVideoFileName, 0, null, size.getWidth(), size.getHeight());
		mPreviewRightBar.refreshCoverBmp();

		LogUtil.LogD(TAG, "onStopRecord   --->");
	}

	private void updateRecordingTime() {
		String strRecordText = null;
		long currentTime = SystemClock.uptimeMillis();
		long delta = currentTime - mRecordingStartTime;
		long nextDelayTime = 1000;

		strRecordText = DateUtil.getFormatTime(delta);
		mRecordTimeBar.setText(strRecordText);

		long actualNextUpdateDelay = nextDelayTime - (delta % nextDelayTime);
		mHandler.sendEmptyMessageDelayed(AppGlobalDef.MessageIds.MSG_UPDATE_RECORDING_TIME, actualNextUpdateDelay);
	}

	private volatile boolean mbDumpEnabled = false;
	private TaskQueueThread mDumpThread = null;
	public static final String DUMP_PATH = Environment.getExternalStorageDirectory().toString() + "/Dump";

	private void enabledDump(boolean enable) {
		if (enable) {
			if (null == mDumpThread) {
				FileUtil.createDirectory(DUMP_PATH);
				mDumpThread = new TaskQueueThread(mDumpListener);
				mDumpThread.start();
			}
			mbDumpEnabled = true;
			mBtnDump.setText(R.string.ids_dump_stop);

		} else {
			mbDumpEnabled = false;
			if (null != mDumpThread) {
				mDumpThread.stop(true);
				mDumpThread = null;
			}
			mBtnDump.setText(R.string.ids_dump_start);
		}
	}

	private final boolean PRINT_FRAME_DURATION = false;
	private long mLastFrameTime = -1;

	private final boolean ENABLE_FRAME_TAG = false;
	private final int POSITION_X = 400;
	private final int POSITION_Y = 300;
	private final int WIDTH = 100;
	private final int HEIGHT = 100;
	private int mIndex = 0;
	private static final int[] COLORS = new int[] {
			0,// black
			127,// gray
			255,// white
			63,
			191, };

	private StringBuffer mRecordingFrameTimeStampBuffer = new StringBuffer();
	@Override
	public void onPreviewFrame(byte[] data, long timeStamp, int width, int height) {
		// LogUtil.LogD(TAG, "onPreviewFrame   <---");

		timeStamp += mSystemSleepTime;
		if (isRecording){
			mRecordingFrameTimeStampBuffer.append("timeStamp: "+timeStamp+"\n");
		}
		LogUtil.LogD(TAG, "####onPreviewFrame, timeStamp=" + timeStamp);
		long now = System.currentTimeMillis();

		if (PRINT_FRAME_DURATION) {
			if (-1 != mLastFrameTime) {
				long duration = now - mLastFrameTime;
				if (duration > 40) {
					LogUtil.LogI(TAG, "onPreviewFrame, duration: " + duration);
				} else if (duration < 30) {
					LogUtil.LogE(TAG, "onPreviewFrame, duration: " + duration);
				} else {
					LogUtil.LogD(TAG, "onPreviewFrame, duration: " + duration);
				}
			}
			mLastFrameTime = now;
		}

		if (ENABLE_FRAME_TAG) {
			int start = POSITION_Y * width + POSITION_X;
			for (int row = 0; row < WIDTH; row++) {
				for (int col = 0; col < HEIGHT; col++) {
					int yIndex = row * width + col;
					data[start + yIndex] = (byte) COLORS[mIndex];
				}
			}
			mIndex = (mIndex + 1) % COLORS.length;
		}

		mOpenGLView.onPreviewFrame(data, width, height, timeStamp);
		mOpenGLView.requestRender();

		if (mbDumpEnabled && null != mDumpThread) {
			// BenchmarkUtil.start("CopyPreview");
			byte[] newData = new byte[data.length];
			System.arraycopy(data, 0, newData, 0, data.length);
			// BenchmarkUtil.stop("CopyPreview");
			//mDumpThread.addTask(new DumpTask(newData, width, height, now));
		}

		// LogUtil.LogD(TAG, "onPreviewFrame   --->");
	}

	private class DumpTask implements Runnable {

		private byte[] mData;
		private int mWidth, mHeight;
		private long mTime;

		public DumpTask(byte[] data, int width, int height, long time) {
			mData = data;
			mWidth = width;
			mHeight = height;
			mTime = time;
		}

		@Override
		public void run() {
			if (null == mData || mWidth <= 0 || mHeight <= 0) {
				LogUtil.LogE(TAG, "invalid parameters.");
				return;
			}
			String date = new SimpleDateFormat("yyyyMMdd_kkmmss_SSS", Locale.getDefault()).format(mTime);
			String fileName = DUMP_PATH + String.format("/%s_%dx%d.NV21", date, mWidth, mHeight)+".txt";
			if (!FileUtil.saveByteArrayToFile(fileName, mData)) {
				LogUtil.LogE(TAG, "save data to file failed.");
			}
		}
	}

	private TaskQueueThread.Listener mDumpListener = new TaskQueueThread.Listener() {

		@Override
		public void onWaitNewTask() {
		}

		@Override
		public void onTaskDone(Runnable task) {
		}

		@Override
		public void onStop() {
			mHandler.post(new Runnable() {
				public void run() {
					Toast.makeText(mContext, "Dump finished.", Toast.LENGTH_LONG).show();
				}
			});
		}

		@Override
		public void onStart() {
		}

		@Override
		public void onResume() {
		}

		@Override
		public void onPause() {
		}
	};

	@Override
	public void onPageOpen(int pageId) {
		LogUtil.LogD(TAG, "onPageOpen  pageId = " + pageId + " <---");

		if (pageId == OpenPageController.PAGE_ID_SETTING_PAGE) {
			mPreviewRightBar.setVisibility(View.GONE);
		}

		LogUtil.LogD(TAG, "onPageOpen   --->");
	}

	@Override
	public void onPageClose(int pageId) {
		LogUtil.LogD(TAG, "onPageClose  pageId = " + pageId + " <---");

		if (pageId == OpenPageController.PAGE_ID_COMPARE_PAGE) {
			mHandler.removeMessages(AppGlobalDef.MessageIds.MSG_SHOW_COMPARE_PAGE);
			mHandler.removeMessages(AppGlobalDef.MessageIds.MSG_CLOSE_COMPARE_PAGE);
		} else if (pageId == OpenPageController.PAGE_ID_SETTING_PAGE) {
			mPreviewRightBar.setVisibility(View.VISIBLE);
		}

		LogUtil.LogD(TAG, "onPageClose   --->");
	}

	@Override
	public int onUiCmd(int key, Object obj) {
		LogUtil.LogD(TAG, "onUiCmd  key = " + key + " <---");

		switch (key) {
		case AppGlobalDef.UiCmdIds.CMD_COMPARE_PAGE_TOUCHED:
			mHandler.removeMessages(AppGlobalDef.MessageIds.MSG_SHOW_COMPARE_PAGE);
			mHandler.removeMessages(AppGlobalDef.MessageIds.MSG_CLOSE_COMPARE_PAGE);
			break;

		case AppGlobalDef.UiCmdIds.CMD_RIGHTBAR_SETTING_CLICKED:
			mOpenPageController.openPage(OpenPageController.PAGE_ID_SETTING_PAGE, true);
			break;
			
		case AppGlobalDef.UiCmdIds.CMD_RIGHTBAR_SWITCH_CLICKED:
			switchCamera();
			break;
		case AppGlobalDef.UiCmdIds.CMD_RIGHTBAR_RECORD_CLICKED:
			if (AppGlobalDef.SUPPORTED_HARDWARE_ENCODING) {
				boolean bRecording = mCamEngine.getCameraState() == CameraState.CAMERA_STATE_RECORDING;
				if (bRecording) {
					LogUtil.LogE(TAG,bRecording+"");
					stopRecord();
				} else {
					LogUtil.LogE(TAG,bRecording+"");
					startRecord();
				}
			} else {
				String msg = mContext.getResources().getString(R.string.ids_not_support_hw_enconding);
				mHelperView.showToast(msg, 2000);
			}
			break;

		case AppGlobalDef.UiCmdIds.CMD_RIGHTBAR_GALLERY_CLICKED:
			int count = MediaManager.getInstance(mContext).getMediaFilesCount();
			if (count > 0) {
				LogUtil.LogD(TAG, count + " files in directory.");
				gotoGallery();
			} else {
				LogUtil.LogD(TAG, "There is no video files in directory.");
				String msg = mContext.getResources().getString(R.string.ids_has_no_video_files);
				mHelperView.showToast(msg, 1000);
			}
			break;
		}

		LogUtil.LogD(TAG, "onUiCmd   --->");

		return 0;
	}

	private void gotoGallery() {
		LogUtil.LogD(TAG, "gotoGallery   <---");
		Uri uri = MediaManager.getInstance(mContext).getMediaFileUri(mVideoFileName);
		if (uri != null) {
			Intent intent = new Intent(Intent.ACTION_VIEW);	
			intent.setData(uri);
			try {
				mContext.startActivity(intent);
			} catch (android.content.ActivityNotFoundException e) {
				e.printStackTrace();
				LogUtil.LogE(TAG, "ActivityNotFoundException action = arcsoft.action.photoview");
			}
		}
		LogUtil.LogD(TAG, "gotoGallery   --->");
	}

	@Override
	public int onConfigChanged(int key, Object obj) {
		LogUtil.LogD(TAG, "onConfigChanged  key = " + key + " <---");

		switch (key) {

		case ConfigMgr.KEY_CONFIG_PREVIEW_SIZE:
			MSize newSize = (MSize) obj;
			MSize oldSize = MSizeUtil.mapCameraSize2MSize(mCamEngine.getPreviewSize());
			if (!newSize.equals(oldSize)) {
				mOpenGLView.restartPreview();
				mCamEngine.stopPreview();
				mCamEngine.setPreviewCallback(null);
				mCamEngine.setPreviewSize(new Size(newSize.width, newSize.height));
				mCamEngine.setPreviewCallback(this);
				setSurfaceSize(newSize.width, newSize.height);
			}
			break;

		case ConfigMgr.KEY_CONFIG_DEBUG_MODE:
			int debugMode = (Integer) obj;
			setDebugMode(debugMode);
			break;

		case ConfigMgr.KEY_CONFIG_COMPARE_MODE:
			int compareMode = (Integer) obj;
			setCompareMode(compareMode);
			// toast
			if (compareMode == ConfigMgr.VALUE_COMPARE_MODE_BEFORE || compareMode == ConfigMgr.VALUE_COMPARE_MODE_AFTER) {
				String msg = mContext.getResources().getString(compareMode == ConfigMgr.VALUE_COMPARE_MODE_BEFORE ? R.string.ids_compare_mode_before : R.string.ids_compare_mode_after);
				mHelperView.showToast(msg, ResourceUtil.getValueFromXML(mContext, R.dimen.toast_textsize_compare), 1000);
			}
			break;
			
		case ConfigMgr.KEY_CONFIG_SENSOR_FREQUENCY:
			int freq = 1000 / (Integer) obj;
			LogUtil.LogD(TAG, "####freq = " + freq);

			if (mSensorType == AppGlobalDef.CONFIG_SENSOR_OFF) {

			} else if (mSensorType == AppGlobalDef.CONFIG_SENSOR_GYROSCOPE) {
				mSensorManager.unregisterListener(mSensorListener, mSensor1);
				mSensorManager.unregisterListener(mSensorListener, mSensor2);
			} else if (mSensorType == AppGlobalDef.CONFIG_SENSOR_ACCELEROMETER_MAGNETIC) {
				mSensorManager.unregisterListener(mSensorListener, mSensor1);
				mSensorManager.unregisterListener(mSensorListener, mSensor2);
			}
			
			if (mSensorType == AppGlobalDef.CONFIG_SENSOR_OFF) {
				
			} else if (mSensorType == AppGlobalDef.CONFIG_SENSOR_GYROSCOPE) {
				mSensor1 = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
				mSensorManager.registerListener(mSensorListener, mSensor1,freq * 1000);
				mSensor2 = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
				mSensorManager.registerListener(mSensorListener, mSensor2, freq * 1000);
			} else if (mSensorType == AppGlobalDef.CONFIG_SENSOR_ACCELEROMETER_MAGNETIC) {
				mSensor1 = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
				mSensor2 = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
				mSensorManager.registerListener(mSensorListener, mSensor1, freq * 1000);
				mSensorManager.registerListener(mSensorListener, mSensor2, freq * 1000);
				
			}		
		default:
			break;
		}

		LogUtil.LogD(TAG, "onConfigChanged   --->");

		return 0;
	}

	private void resetPerformanceInfo() {
		LogUtil.LogD(TAG, "resetPerformanceInfo   <---");

		mOpenGLView.resetPerformanceInfo();
		if (null != mCamEngine) {
			mCamEngine.resetPerformanceInfo();
		}

		LogUtil.LogD(TAG, "resetPerformanceInfo   --->");
	}

	@Override
	public boolean handleMessage(Message msg) {
//		LogUtil.LogD(TAG, "handleMessage   <---");
//		LogUtil.LogD(TAG, "handleMessage   what = " + msg.what);

		boolean res = true;

		switch (msg.what) {
			case AppGlobalDef.MessageIds.MSG_UPDATE_PERFORMANCE_INFO:
				int debugMode = (Integer) mConfigMgr.getConfig(ConfigMgr.KEY_CONFIG_DEBUG_MODE);
				if (debugMode != ConfigMgr.VALUE_DEBUG_MODE_OFF) {
					mHandler.removeMessages(AppGlobalDef.MessageIds.MSG_UPDATE_PERFORMANCE_INFO);
					String strInfo = mOpenGLView.getPerformanceInfo();
					if (debugMode == ConfigMgr.VALUE_DEBUG_MODE_DEBUG) {
						strInfo += mCamEngine.getPerformanceInfo();
					}
					if (null != strInfo && null != mInfoView) {
						mInfoView.setText(strInfo);
					}
				// text color
					boolean lowFps = mOpenGLView.isLowFPS();
					int color = lowFps ? Color.RED : ResourceUtil.getColor(mContext, R.color.info_view_text_color);
					mInfoView.setTextColor(color);
					mHandler.sendEmptyMessageDelayed(AppGlobalDef.MessageIds.MSG_UPDATE_PERFORMANCE_INFO, 500);
				}
				break;

			case AppGlobalDef.MessageIds.MSG_SHOW_COMPARE_PAGE:
				if (mCamEngine.getCameraState() != CameraState.CAMERA_STATE_RECORDING) {
					mOpenPageController.openPage(OpenPageController.PAGE_ID_COMPARE_PAGE, true);
				}
				break;

			case AppGlobalDef.MessageIds.MSG_CLOSE_COMPARE_PAGE:
				if (mOpenPageController.isPageOpened()) {
					if (mOpenPageController.getOpenPage() instanceof ComparePage) {
						mOpenPageController.closePage(true);
					}
				}
				break;

			case AppGlobalDef.MessageIds.MSG_UPDATE_RECORDING_TIME:
				updateRecordingTime();
				break;
			case WRITE_DONE :
				Toast.makeText(mContext,"done",Toast.LENGTH_SHORT).show();


			default:
				res = false;
				break;
		}

//		LogUtil.LogD(TAG, "handleMessage   --->");
		return res;
	}

	@Override
	public int onNotify(int key, Object obj) {

		LogUtil.LogD(TAG, "onNotify  key = " + key + " <---");

		switch (key) {
		case AppGlobalDef.NotifyIds.NOTIFY_ON_CAMERA_ERROR:
			break;

		case AppGlobalDef.NotifyIds.NOTIFY_ON_START_RECORDING:
			onStartRecord();
			break;

		case AppGlobalDef.NotifyIds.NOTIFY_ON_STOP_RECORDING:
			onStopRecord();
			break;

		default:
			break;
		}

		LogUtil.LogD(TAG, "onNotify   --->");

		return 0;
	}

	@Override
	public boolean onDown(MotionEvent e) {
		LogUtil.LogD(TAG, "onDown   <---");
		LogUtil.LogD(TAG, "onDown   --->");
		return true;
	}

	@Override
	public void onShowPress(MotionEvent e) {
		LogUtil.LogD(TAG, "onShowPress   <---");
		LogUtil.LogD(TAG, "onShowPress   --->");
	}

	@Override
	public boolean onSingleTapUp(MotionEvent e) {
		LogUtil.LogD(TAG, "onSingleTapUp   <---");

		if (mCamEngine.getCameraState() != CameraState.CAMERA_STATE_RECORDING) {
			if (mOpenPageController.isPageOpened()) {
				mOpenPageController.closePage(true);
			} else {
				mOpenPageController.openPage(OpenPageController.PAGE_ID_COMPARE_PAGE, true);
			}
		}

		LogUtil.LogD(TAG, "onSingleTapUp   --->");
		return true;
	}

	@Override
	public void onLongPress(MotionEvent e) {
		LogUtil.LogD(TAG, "onLongPress   <---");
		LogUtil.LogD(TAG, "onLongPress   --->");
	}

	@Override
	public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
		LogUtil.LogD(TAG, "onScroll   <---");
		LogUtil.LogD(TAG, "onScroll   --->");
		return true;
	}

	@Override
	public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
		LogUtil.LogD(TAG, "onFling   <---");
		LogUtil.LogD(TAG, "velocityX = " + velocityX + ", velocityY = " + velocityY);
		boolean mbDirectionY = Math.abs(velocityY) > Math.abs(velocityX) ? true : false;
		if (mbDirectionY) {
			if (Math.abs(velocityY) > 500) {
				if (e2.getY() - e1.getY() > 100) {
					// from up to down
				} else if (e1.getY() - e2.getY() > 100) {
					// from down to up
				}
			}
		}
		LogUtil.LogD(TAG, "onFling   --->");
		return true;
	}

	public boolean allowDispatchTouchEvent(MotionEvent event) {
		boolean appBusy = (Boolean) mConfigMgr.getConfig(ConfigMgr.KEY_CONFIG_APP_BUSY);
		if (appBusy) {
			return false;
		} else {
			return true;
		}
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (mOpenPageController.onKeyDown(keyCode, event)) {
			return true;
		}
		if (keyCode == KeyEvent.KEYCODE_BACK && mCamEngine.getCameraState() == CameraState.CAMERA_STATE_RECORDING) {
			return true;
		}
		return super.onKeyDown(keyCode, event);
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if (mOpenPageController.onKeyUp(keyCode, event)) {
			return true;
		}
		if (keyCode == KeyEvent.KEYCODE_BACK && mCamEngine.getCameraState() == CameraState.CAMERA_STATE_RECORDING) {
			stopRecord();
			return true;
		}
		return super.onKeyUp(keyCode, event);
	}

	private StringBuffer mAccelerometerBuffer = new StringBuffer();
	private StringBuffer mMagneticFieldBuffer = new StringBuffer();
	private StringBuffer mGyroscopeBuffer = new StringBuffer();
	private StringBuffer mRotationVectorBuffer = new StringBuffer();
	
	private SensorEventListener mSensorListener = new SensorEventListener() {
		public void onSensorChanged(SensorEvent e) {
			mSensorArray[0] = e.values[0];
			mSensorArray[1] = e.values[1];
			mSensorArray[2] = e.values[2];
			mSensorTimeStamp = e.timestamp;
			int type = e.sensor.getType();
			//LogUtil.LogD(TAG, "onSensorChanged, type="+type+", sensorData=[" + e.values[0] + ", " + e.values[1] + ", " + e.values[2] + "], timeStamp=" + e.timestamp);
			LogUtil.LogD(TAG, "####onSensorChanged, timeStamp=" + e.timestamp);
			
			if (type == Sensor.TYPE_ACCELEROMETER) {
				if (isRecording){
					mAccelerometerBuffer.append("timestamp: "+e.timestamp+"--->"+"value: "+e.values[0]).append("\n");
				}

				JNI.native_setSensorInfo(AppGlobalDef.NATIVE_SENSOR_ACCELEROMETER, mSensorArray, mSensorTimeStamp);
			} else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
				if (isRecording){
					mMagneticFieldBuffer.append("timestamp: "+e.timestamp+"--->"+"value: "+e.values[0]).append("\n");
				}
				JNI.native_setSensorInfo(AppGlobalDef.NATIVE_SENSOR_MAGNETIC, mSensorArray, mSensorTimeStamp);
			} else if (type == Sensor.TYPE_GYROSCOPE) {
				if (isRecording){
					mGyroscopeBuffer.append("timestamp: "+e.timestamp+"--->"+"value: "+e.values[0]).append("\n");
				}
				JNI.native_setSensorInfo(AppGlobalDef.NATIVE_SENSOR_GYROSCOPE, mSensorArray, mSensorTimeStamp);
			}else if (type == Sensor.TYPE_ROTATION_VECTOR) {
				if (isRecording){
					mRotationVectorBuffer.append("timestamp: "+e.timestamp+"--->"+"value: "+e.values[0]).append("\n");
				}
				JNI.native_setSensorInfo(AppGlobalDef.NATIVE_SENSOR_ROTATION_VECTOR, mSensorArray, mSensorTimeStamp);
			}
		}

		public void onAccuracyChanged(Sensor s, int accuracy) {
		}
	};
}
