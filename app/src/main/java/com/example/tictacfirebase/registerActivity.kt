package com.example.tictacfirebase

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.tictacfirebase.model.User
import com.example.tictacfirebase.service.MyFirebaseMessagingService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.*

class registerActivity : AppCompatActivity() {

    companion object {
        private const val tag = "RegisterActivity"

    }

    private lateinit var register_progressBar: ProgressBar
    private lateinit var register_button_register: Button
    private lateinit var already_have_accaunt_text_view: android.widget.TextView
    private lateinit var select_photo_button_register: Button
    private lateinit var select_photoview_register: CircleImageView
    private lateinit var email_edittext_register: EditText
    private lateinit var password_edittext_register: EditText
    private lateinit var username_edittext_register: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)
        
        register_progressBar = findViewById(R.id.register_progressBar)
        register_button_register = findViewById(R.id.register_button_register)
        already_have_accaunt_text_view = findViewById(R.id.already_have_accaunt_text_view)
        select_photo_button_register = findViewById(R.id.select_photo_button_register)
        select_photoview_register = findViewById(R.id.select_photoview_register)
        email_edittext_register = findViewById(R.id.email_edittext_register)
        password_edittext_register = findViewById(R.id.password_edittext_register)
        username_edittext_register = findViewById(R.id.username_edittext_register)
        
        register_progressBar.scaleY = 4f
        register_progressBar.visibility = View.GONE

        register_button_register.setOnClickListener {
            performRegister()
        }

        already_have_accaunt_text_view.setOnClickListener {
            Log.d(tag, "Try to show LoginActivity activity")

            // launch the LoginActivity activity somehow
            val intent = Intent(this, LoginActivity::class.java)

            startActivity(intent)
        }

        select_photo_button_register.setOnClickListener {
            Log.d(tag, "Try to show photo selector")

            val intent = Intent(Intent.ACTION_PICK)
            intent.type = "image/*"
            startActivityForResult(intent, 0)
        }
    }

    var selectedPhotoUri: Uri? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 0 && resultCode == Activity.RESULT_OK && data != null) {
            // proceed and check what the selected image was....
            Log.d(tag, "Photo was selected")

            selectedPhotoUri = data.data

            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, selectedPhotoUri)

            select_photoview_register.setImageBitmap(bitmap)

            select_photo_button_register.alpha = 0f

//      val bitmapDrawable = BitmapDrawable(bitmap)
//      selectphoto_button_register.setBackgroundDrawable(bitmapDrawable)
        }
    }

    private fun performRegister() {
        val email = email_edittext_register.text.toString().trim()
        val password = password_edittext_register.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please enter text in email/pw", Toast.LENGTH_SHORT).show()
            hideLoading()
            return
        }

        Log.d(tag, "Attempting to create user with email: $email")
        
        showLoading()

        // Firebase Authentication to create a user with email and password
        FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        hideLoading()
                        return@addOnCompleteListener
                    }

                    // else if successful
                    val user = task.result?.user ?: return@addOnCompleteListener
                    Log.d(tag, "Successfully created user with uid: ${user.uid}")

                    uploadImageToFirebaseStorage()
                }
                .addOnFailureListener {
                    hideLoading()
                    Log.d(tag, "Failed to create user: ${it.message}")
                    Toast.makeText(this, "Failed to create user: ${it.message}", Toast.LENGTH_SHORT).show()
                }
    }

    private fun uploadImageToFirebaseStorage() {
        if (selectedPhotoUri == null) {
            // No photo selected, proceed directly to save user
            saveUserToFirebaseDatabase("")
            return
        }

        val filename = UUID.randomUUID().toString()
        val ref = FirebaseStorage.getInstance().getReference("/images/$filename")
        
        lifecycleScope.launch {
            try {
                val uploadTask = ref.putFile(selectedPhotoUri!!).await()
                Log.d(tag, "Successfully uploaded image: ${uploadTask.metadata?.path}")

                val downloadUrl = ref.downloadUrl.await()
                Log.d(tag, "File Location: $downloadUrl")

                saveUserToFirebaseDatabase(downloadUrl.toString())
            } catch (e: Exception) {
                hideLoading()
                Log.d(tag, "Failed to upload image or get URL: ${e.message}")
                Toast.makeText(this@registerActivity, "Failed to process image: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveUserToFirebaseDatabase(profileImageUrl: String) {
        val email = email_edittext_register.text.toString().trim()
        val stripEmail = SplitString(email)
        val Request = ""

        val uid = FirebaseAuth.getInstance().uid ?: ""

        val ref = FirebaseDatabase.getInstance().getReference("/users/$stripEmail")
//        refreshTokens()
        val newTokens = refreshTokens().toString()


        val user = User(uid, Request, username_edittext_register.text.toString(), profileImageUrl, newTokens)

        ref.setValue(user)
                .addOnSuccessListener {
                    Log.d(tag, "Finally we saved the user to Firebase Database")

                    val intent = Intent(this, MainActivity::class.java)
                    intent.putExtra("email", email.toString().trim())
                    Log.d(tag, "putExtraEmail: $email")
                    intent.putExtra("uid", uid)
                    Log.d(tag, "putExtraUid: ${uid}")
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK.or(Intent.FLAG_ACTIVITY_NEW_TASK)
//                refreshTokens()
                    hideLoading()
                    startActivity(intent)

                }
                .addOnFailureListener {
                    hideLoading()
                    Log.d(tag, "Failed to set value to database: ${it.message}")
                }
    }

    /**
     * Показать индикатор загрузки
     */
    private fun showLoading() {
        register_progressBar.visibility = View.VISIBLE
    }

    /**
     * Скрыть индикатор загрузки
     */
    private fun hideLoading() {
        register_progressBar.visibility = View.GONE
    }

    fun SplitString(str: String): String {
        var split = str.split("@")
        return split[0]
    }

    private fun refreshTokens(): String? {
        var newToken: String? = null
        
        lifecycleScope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                Log.d("FCM_TOKEN", "Token fetched successfully: $token")
                
                if (token != null) {
                    withContext(Dispatchers.IO) {
                        MyFirebaseMessagingService().saveTokenToFirebaseDatabase(token)
                    }
                    newToken = token
                }
            } catch (e: Exception) {
                Log.w(tag, "Fetching FCM registration token failed", e)
                // Не скрываем loading здесь, т.к. это вспомогательная функция
            }
        }
        return newToken
    }


}
