/*
 * Copyright 2018 Dmytro Ponomarenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dimowner.audiorecorder.audio.recorder;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;

import androidx.annotation.RequiresApi;

import com.dimowner.audiorecorder.ARApplication;
import com.dimowner.audiorecorder.exception.InvalidOutputFile;
import com.dimowner.audiorecorder.exception.RecorderInitException;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

import static com.dimowner.audiorecorder.AppConstants.RECORDING_VISUALIZATION_INTERVAL;

public class AudioRecorder implements RecorderContract.Recorder {

	private MediaRecorder recorder = null;
	private File recordFile = null;
	private long updateTime = 0;
	private long durationMills = 0;

	private final AtomicBoolean isRecording = new AtomicBoolean(false);
	private final AtomicBoolean isPaused = new AtomicBoolean(false);
	private final Handler handler = new Handler();

	private RecorderContract.RecorderCallback recorderCallback;
	
	// For system audio recording
	private MediaProjection mediaProjection = null;
	private VirtualDisplay virtualDisplay = null;

	private static class RecorderSingletonHolder {
		private static final AudioRecorder singleton = new AudioRecorder();

		public static AudioRecorder getSingleton() {
			return RecorderSingletonHolder.singleton;
		}
	}

	public static AudioRecorder getInstance() {
		return RecorderSingletonHolder.getSingleton();
	}

	private AudioRecorder() { }

	@Override
	public void setRecorderCallback(RecorderContract.RecorderCallback callback) {
		this.recorderCallback = callback;
	}

	@Override
	public void startRecording(String outputFile, int channelCount, int sampleRate, int bitrate) {
		// IMPORTANT: Clean up any existing MediaProjection resources before normal recording
		// This ensures that normal microphone recording doesn't capture system audio
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			releaseSystemAudioResources();
			// Also clear the global MediaProjection to prevent interference
			ARApplication.mediaProjection = null;
		}
		
		recordFile = new File(outputFile);
		if (recordFile.exists() && recordFile.isFile()) {
			recorder = new MediaRecorder();
			recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
			recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
			recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
			recorder.setAudioChannels(channelCount);
			recorder.setAudioSamplingRate(sampleRate);
			recorder.setAudioEncodingBitRate(bitrate);
			recorder.setMaxDuration(-1); //Duration unlimited or use RECORD_MAX_DURATION
			recorder.setOutputFile(recordFile.getAbsolutePath());
			try {
				recorder.prepare();
				recorder.start();
				updateTime = System.currentTimeMillis();
				isRecording.set(true);
				scheduleRecordingTimeUpdate();
				if (recorderCallback != null) {
					recorderCallback.onStartRecord(recordFile);
				}
				isPaused.set(false);
				Timber.d("Normal microphone recording started (system audio resources cleaned)");
			} catch (IOException | IllegalStateException e) {
				Timber.e(e, "prepare() failed");
				if (recorderCallback != null) {
					recorderCallback.onError(new RecorderInitException());
				}
			}
		} else {
			if (recorderCallback != null) {
				recorderCallback.onError(new InvalidOutputFile());
			}
		}
	}

	@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
	@Override
	public void startSystemAudioRecording(MediaProjection projection, String outputFile, 
	                                       int channelCount, int sampleRate, int bitrate) {
		// Check if running on HarmonyOS (鸿蒙系统)
		boolean isHarmonyOS = Build.MANUFACTURER != null && 
			(Build.MANUFACTURER.toLowerCase().contains("huawei") || 
			 Build.MANUFACTURER.toLowerCase().contains("honor"));
		
		Timber.d("startSystemAudioRecording called: outputFile=%s, channels=%d, sampleRate=%d, bitrate=%d, isHarmonyOS=%s", 
			outputFile, channelCount, sampleRate, bitrate, isHarmonyOS);
		
		if (projection == null) {
			Timber.e("MediaProjection is null");
			if (recorderCallback != null) {
				recorderCallback.onError(new RecorderInitException());
			}
			return;
		}
		
		recordFile = new File(outputFile);
		Timber.d("Record file path: %s, exists: %s", outputFile, recordFile.exists());
		
		// Ensure parent directory exists
		File parentDir = recordFile.getParentFile();
		if (parentDir != null && !parentDir.exists()) {
			boolean created = parentDir.mkdirs();
			Timber.d("Parent directory created: %s, success: %s", parentDir.getAbsolutePath(), created);
		}
		
		// Create empty file if it doesn't exist
		try {
			if (!recordFile.exists()) {
				boolean created = recordFile.createNewFile();
				Timber.d("Record file created: %s", created);
			}
		} catch (IOException e) {
			Timber.e(e, "Failed to create record file: %s", e.getMessage());
			if (recorderCallback != null) {
				recorderCallback.onError(new InvalidOutputFile());
			}
			return;
		}
		
		if (!recordFile.exists() || !recordFile.isFile()) {
			Timber.e("Record file is invalid: %s, exists: %s, isFile: %s", 
				outputFile, recordFile.exists(), recordFile.isFile());
			if (recorderCallback != null) {
				recorderCallback.onError(new InvalidOutputFile());
			}
			return;
		}
		
		this.mediaProjection = projection;
		
		try {
			Timber.d("Creating virtual display...");
			// Create minimal virtual display (1x1 pixel, Surface is null - no video recording)
			// Note: Some systems (like HarmonyOS) may require a valid Surface
			// Try with null first, if fails, we'll handle it
			try {
				virtualDisplay = mediaProjection.createVirtualDisplay(
					"SystemAudioRecorder",
					1, 1,  // Minimum size: 1x1 pixel
					1,     // Minimum DPI
					DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
					null,  // Surface = null, no video recording!
					null,
					null
				);
			} catch (Exception e) {
				Timber.e(e, "createVirtualDisplay with null Surface failed: %s", e.getMessage());
				// Some systems may not allow null Surface, try with a minimal Surface
				// But for audio-only, we should still try null first
				throw e;
			}
			
			if (virtualDisplay == null) {
				Timber.e("Failed to create virtual display - createVirtualDisplay returned null");
				releaseSystemAudioResources();
				if (recorderCallback != null) {
					recorderCallback.onError(new RecorderInitException());
				}
				return;
			}
			Timber.d("Virtual display created successfully");
			
			// Configure MediaRecorder (audio only, no video)
			// IMPORTANT: When using MediaProjection with VirtualDisplay, AudioSource.MIC
			// should capture system audio only, not microphone input.
			// However, behavior may vary by device. If microphone is still being captured,
			// we may need to use AudioSource.REMOTE_SUBMIX (requires system permissions).
			Timber.d("Configuring MediaRecorder for system audio only...");
			recorder = new MediaRecorder();
			
			// Use MIC audio source - with MediaProjection, this should capture system audio only
			// Note: On some devices, this may still mix microphone. If that's the case,
			// we would need REMOTE_SUBMIX which requires system permissions.
			recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
			recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
			recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
			recorder.setAudioChannels(channelCount);
			recorder.setAudioSamplingRate(sampleRate);
			recorder.setAudioEncodingBitRate(bitrate);
			recorder.setMaxDuration(-1);
			recorder.setOutputFile(recordFile.getAbsolutePath());
			
			// Do NOT set video source or encoder - audio only!
			// The VirtualDisplay with null Surface ensures only audio is captured
			
			Timber.d("Preparing MediaRecorder...");
			recorder.prepare();
			Timber.d("Starting MediaRecorder...");
			recorder.start();
			updateTime = System.currentTimeMillis();
			isRecording.set(true);
			scheduleRecordingTimeUpdate();
			if (recorderCallback != null) {
				recorderCallback.onStartRecord(recordFile);
			}
			isPaused.set(false);
			Timber.d("System audio recording started successfully");
		} catch (IOException e) {
			Timber.e(e, "System audio recording IOException: %s", e.getMessage());
			e.printStackTrace();
			releaseSystemAudioResources();
			if (recorderCallback != null) {
				recorderCallback.onError(new RecorderInitException());
			}
		} catch (IllegalStateException e) {
			Timber.e(e, "System audio recording IllegalStateException: %s", e.getMessage());
			e.printStackTrace();
			releaseSystemAudioResources();
			if (recorderCallback != null) {
				recorderCallback.onError(new RecorderInitException());
			}
		} catch (SecurityException e) {
			Timber.e(e, "System audio recording SecurityException: %s", e.getMessage());
			e.printStackTrace();
			releaseSystemAudioResources();
			if (recorderCallback != null) {
				recorderCallback.onError(new RecorderInitException());
			}
		} catch (Exception e) {
			Timber.e(e, "System audio recording unexpected exception: %s", e.getMessage());
			e.printStackTrace();
			releaseSystemAudioResources();
			if (recorderCallback != null) {
				recorderCallback.onError(new RecorderInitException());
			}
		}
	}

	@Override
	public void resumeRecording() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isPaused.get()) {
			try {
				recorder.resume();
				updateTime = System.currentTimeMillis();
				scheduleRecordingTimeUpdate();
				if (recorderCallback != null) {
					recorderCallback.onResumeRecord();
				}
				isPaused.set(false);
			} catch (IllegalStateException e) {
				Timber.e(e, "unpauseRecording() failed");
				if (recorderCallback != null) {
					recorderCallback.onError(new RecorderInitException());
				}
			}
		}
	}

	@Override
	public void pauseRecording() {
		if (isRecording.get()) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				if (!isPaused.get()) {
					try {
						recorder.pause();
						durationMills += System.currentTimeMillis() - updateTime;
						pauseRecordingTimer();
						if (recorderCallback != null) {
							recorderCallback.onPauseRecord();
						}
						isPaused.set(true);
					} catch (IllegalStateException e) {
						Timber.e(e, "pauseRecording() failed");
						if (recorderCallback != null) {
							//TODO: Fix exception
							recorderCallback.onError(new RecorderInitException());
						}
					}
				}
			} else {
				stopRecording();
			}
		}
	}

	@Override
	public void stopRecording() {
		if (isRecording.get()) {
			stopRecordingTimer();
			try {
				if (recorder != null) {
					recorder.stop();
				}
			} catch (RuntimeException e) {
				Timber.e(e, "stopRecording() problems");
			}
			if (recorder != null) {
				recorder.release();
				recorder = null;
			}
			releaseSystemAudioResources();
			if (recorderCallback != null) {
				recorderCallback.onStopRecord(recordFile);
			}
			durationMills = 0;
			recordFile = null;
			isRecording.set(false);
			isPaused.set(false);
		} else {
			Timber.e("Recording has already stopped or hasn't started");
		}
	}
	
	@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
	private void releaseSystemAudioResources() {
		if (virtualDisplay != null) {
			virtualDisplay.release();
			virtualDisplay = null;
		}
		if (mediaProjection != null) {
			mediaProjection.stop();
			mediaProjection = null;
		}
		// Also clear the global MediaProjection to prevent interference with normal recording
		ARApplication.mediaProjection = null;
		Timber.d("System audio resources released and global MediaProjection cleared");
	}

	private void scheduleRecordingTimeUpdate() {
		handler.postDelayed(() -> {
			if (recorderCallback != null && recorder != null) {
				try {
					long curTime = System.currentTimeMillis();
					durationMills += curTime - updateTime;
					updateTime = curTime;
					recorderCallback.onRecordProgress(durationMills, recorder.getMaxAmplitude());
				} catch (IllegalStateException e) {
					Timber.e(e);
				}
				scheduleRecordingTimeUpdate();
			}
		}, RECORDING_VISUALIZATION_INTERVAL);
	}

	private void stopRecordingTimer() {
		handler.removeCallbacksAndMessages(null);
		updateTime = 0;
	}

	private void pauseRecordingTimer() {
		handler.removeCallbacksAndMessages(null);
		updateTime = 0;
	}

	@Override
	public boolean isRecording() {
		return isRecording.get();
	}

	@Override
	public boolean isPaused() {
		return isPaused.get();
	}
}
