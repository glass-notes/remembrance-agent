package io.p13i.ra.input.speech;
// Imports the Google Cloud client library

import com.google.api.gax.rpc.ClientStream;
import com.google.api.gax.rpc.ResponseObserver;
import com.google.api.gax.rpc.StreamController;
import com.google.cloud.speech.v1.RecognitionConfig;
import com.google.cloud.speech.v1.SpeechClient;
import com.google.cloud.speech.v1.SpeechRecognitionAlternative;
import com.google.cloud.speech.v1.StreamingRecognitionConfig;
import com.google.cloud.speech.v1.StreamingRecognitionResult;
import com.google.cloud.speech.v1.StreamingRecognizeRequest;
import com.google.cloud.speech.v1.StreamingRecognizeResponse;
import com.google.protobuf.ByteString;
import io.p13i.ra.utils.LoggerUtils;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.logging.Logger;

public class SpeechRecognizer {

    private static Logger LOGGER = LoggerUtils.getLogger(SpeechRecognizer.class);
    private OnRecognize mOnRecognizeCallback;

    /**
     * Demonstrates using the Speech API to transcribe an audio file.
     */
    public static void main(String... args) {
    }

    public SpeechRecognizer(SpeechRecognizer.OnRecognize onRecognizeCallback) {
        mOnRecognizeCallback = onRecognizeCallback;
    }

    /**
     * Performs microphone streaming speech recognition with a duration of 1 minute.
     */
    public void recognizeFromMicrophone(int durationSecs) {

        ResponseObserver<StreamingRecognizeResponse> responseObserver =
                new ResponseObserver<StreamingRecognizeResponse>() {
                    ArrayList<StreamingRecognizeResponse> responses = new ArrayList<>();

                    public void onStart(StreamController controller) {
                    }

                    public void onResponse(StreamingRecognizeResponse response) {
                        LOGGER.info("Got response: " + getTranscript(response));
                        responses.add(response);
                    }

                    public void onComplete() {
                        StringBuilder stringBuilder = new StringBuilder();
                        for (StreamingRecognizeResponse response : responses) {
                            String transcript = getTranscript(response);
                            LOGGER.info("Transcript : " + getTranscript(response));
                            stringBuilder.append(transcript);
                            mOnRecognizeCallback.onRecognize(stringBuilder.toString(), transcript);
                        }
                    }

                    public String getTranscript(StreamingRecognizeResponse response) {
                        StreamingRecognitionResult result = response.getResultsList().get(0);
                        SpeechRecognitionAlternative alternative = result.getAlternativesList().get(0);
                        return alternative.getTranscript();
                    }

                    public void onError(Throwable t) {
                        LOGGER.throwing(SpeechRecognizer.class.getSimpleName(), "onError", t);
                        throw new RuntimeException(t);
                    }
                };

        try (SpeechClient client = SpeechClient.create()) {

            ClientStream<StreamingRecognizeRequest> clientStream = client
                    .streamingRecognizeCallable()
                    .splitCall(responseObserver);

            RecognitionConfig recognitionConfig =  RecognitionConfig.newBuilder()
                    .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                    .setLanguageCode("en-US")
                    .setSampleRateHertz(16000)
                    .build();

            StreamingRecognitionConfig streamingRecognitionConfig = StreamingRecognitionConfig
                    .newBuilder()
                    .setConfig(recognitionConfig)
                    .build();

            StreamingRecognizeRequest request = StreamingRecognizeRequest
                    .newBuilder()
                    .setStreamingConfig(streamingRecognitionConfig)
                    .build(); // The first request in a streaming call has to be a config

            clientStream.send(request);

            AudioFormat audioFormat = new AudioFormat(16000, 16, 1, true, false);
            DataLine.Info targetInfo = new DataLine.Info(TargetDataLine.class, audioFormat); // Set the system information to read from the microphone audio stream

            if (!AudioSystem.isLineSupported(targetInfo)) {
                LOGGER.warning("Microphone not supported");
                return;
            }

            // Target data line captures the audio stream the microphone produces.
            TargetDataLine targetDataLine = (TargetDataLine) AudioSystem.getLine(targetInfo);
            targetDataLine.open(audioFormat);
            targetDataLine.start();

            LOGGER.info("Start speaking");

            long startTime = System.currentTimeMillis();
            // Audio Input Stream
            AudioInputStream audio = new AudioInputStream(targetDataLine);

            while (true) {

                long estimatedTime = System.currentTimeMillis() - startTime;
                byte[] data = new byte[6400];
                int bytesRead = audio.read(data);

                if (estimatedTime > durationSecs * 1000) {
                    LOGGER.info("Stop speaking.");
                    targetDataLine.stop();
                    targetDataLine.close();
                    break;
                }
                request = StreamingRecognizeRequest
                        .newBuilder()
                        .setAudioContent(ByteString.copyFrom(data))
                        .build();
                clientStream.send(request);

            }
        } catch (Exception e) {
            LOGGER.throwing(SpeechRecognizer.class.getSimpleName(), "recognizeFromMicrophone", e);
            throw new RuntimeException(e);
        }

        responseObserver.onComplete();
    }

    public interface OnRecognize {
        void onRecognize(String cumilativeTranscript, String newText);
    }
}
