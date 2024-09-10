![signapp-removebg-preview](https://github.com/user-attachments/assets/7e5124c0-ca07-418f-8b64-75ee55402aed)
![example workflow](https://github.com/david-oruna/signapp/actions/workflows/main.yml/badge.svg)


### Overview 

Signapp is an end to end deep learning project for recognizing sign language. The LSTM (a type of Recurrent Neural Network) model was trained using keypoints from videos. The keypoints were obtained using MediaPipe [HandLandmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker). You can check the the model/ directoy for more details about the data processing and training. Then the model was compiled to TFLite and implemented in the Android application. The app also has features for editing the translated message.

This LSTM model, since it makes time series predictions, allows to identify signs in movement.

![image](https://github.com/user-attachments/assets/fa638b72-5900-4091-ac6c-a71aa63298b1)


### Testing ✅
You can check the available signs here
![image](https://github.com/user-attachments/assets/0ecee287-c102-4a3e-967f-d3e7677861f6)

### Running locally 🚀

Follow the instructions in the app directory. 

### Contributions 🙌

If you want to contribute to the dataset, please enter here.

Pull Requests are welcome 😇
