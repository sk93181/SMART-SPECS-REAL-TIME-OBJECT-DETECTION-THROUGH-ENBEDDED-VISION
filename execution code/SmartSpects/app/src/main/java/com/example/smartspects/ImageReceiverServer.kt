package com.example.smartspects

import fi.iki.elonen.NanoHTTPD
import android.graphics.BitmapFactory
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import java.util.*

class ImageReceiverServer(
    private val tts: TextToSpeech,
    private val imageView: ImageView,
    private val objectLabel: TextView
) : NanoHTTPD(8080) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (session.method == Method.POST && session.uri == "/upload") {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val imageStream = session.inputStream

                val bitmap = BitmapFactory.decodeStream(imageStream)

                imageView.post {
                    imageView.setImageBitmap(bitmap)
                    objectLabel.text = "Detected Object: (placeholder)"
                    tts.speak("Detected object", TextToSpeech.QUEUE_FLUSH, null, null)
                }

                newFixedLengthResponse("Image received and shown")
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Invalid request")
            }
        } catch (e: Exception) {
            Log.e("Server", "Error: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server error")
        }
    }
}

