package com.google.mediapipe.examples.gesturerecognizer.fragment

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import com.google.mediapipe.examples.gesturerecognizer.BaseActivity
import com.google.mediapipe.examples.gesturerecognizer.R

class ResultActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)
        val txtResult = findViewById<EditText>(R.id.resultText)
        val result = intent.getStringExtra("RECOGNIZED_GESTURES")
        // log result
        Log.d("Recognized gestures", "Result: $result")
        txtResult.setText(result)
    }

    override fun onBackPressed() {
        val txtResult = findViewById<EditText>(R.id.resultText)
        val edText = txtResult.text.toString()

        // pass the edited text back
        val resIntent = Intent()
        resIntent.putExtra("EDITED_GESTURES", edText)
        setResult(RESULT_OK, resIntent)
        finish()
    }
}