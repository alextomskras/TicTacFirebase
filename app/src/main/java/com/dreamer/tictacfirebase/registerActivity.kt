package com.dreamer.tictacfirebase

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dreamer.tictacfirebase.models.User
import com.dreamer.tictacfirebase.service.MyFirebaseMessagingService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.iid.FirebaseInstanceId
import com.google.firebase.storage.FirebaseStorage
import kotlinx.android.synthetic.main.activity_register.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*

class registerActivity : AppCompatActivity() {

    companion object {
        val TAG = "RegisterActivity"

    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)
        register_progressBar.scaleY = 4f
        register_progressBar.visibility = View.GONE

        register_button_register.setOnClickListener {
            register_progressBar.visibility = View.VISIBLE
            performRegister()
        }

        already_have_accaunt_text_view.setOnClickListener {
            Log.d(TAG, "Try to show LoginActivity activity")

            // launch the LoginActivity activity somehow
            val intent = Intent(this, LoginActivity::class.java)

            startActivity(intent)
        }

        select_photo_button_register.setOnClickListener {
            Log.d(TAG, "Try to show photo selector")

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
            Log.d(TAG, "Photo was selected")

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
            return
        }

        Log.d(TAG, "Attempting to create user with email: $email")

        // Firebase Authentication to create a user with email and password
        FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener {
                    if (!it.isSuccessful) return@addOnCompleteListener

                    // else if successful
                    Log.d(TAG, "Successfully created user with uid: ${it.result!!.user.uid}")

                    uploadImageToFirebaseStorage()
                }
                .addOnFailureListener {
                    register_progressBar.visibility = View.GONE
                    Log.d(TAG, "Failed to create user: ${it.message}")
                    Toast.makeText(this, "Failed to create user: ${it.message}", Toast.LENGTH_SHORT).show()
                }
    }

    private fun uploadImageToFirebaseStorage() {
        if (selectedPhotoUri == null) return

        val filename = UUID.randomUUID().toString()
        val ref = FirebaseStorage.getInstance().getReference("/images/$filename")
        GlobalScope.launch(Dispatchers.IO) {
            ref.putFile(selectedPhotoUri!!)
                    .addOnSuccessListener {
                        Log.d(TAG, "Successfully uploaded image: ${it.metadata?.path}")

                        ref.downloadUrl.addOnSuccessListener {
                            Log.d(TAG, "File Location: $it")

                            saveUserToFirebaseDatabase(it.toString())
                        }
                    }
                    .addOnFailureListener {
                        register_progressBar.visibility = View.GONE
                        Log.d(TAG, "Failed to upload image to storage: ${it.message}")
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
                    Log.d(TAG, "Finally we saved the user to Firebase Database")

                    val intent = Intent(this, MainActivity::class.java)
                    intent.putExtra("email", email.toString().trim())
                    Log.d(TAG, "putExtraEmail: $email")
                    intent.putExtra("uid", uid)
                    Log.d(TAG, "putExtraUid: ${uid}")
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK.or(Intent.FLAG_ACTIVITY_NEW_TASK)
//                refreshTokens()
                    register_progressBar.visibility = View.GONE
                    startActivity(intent)

                }
                .addOnFailureListener {
                    register_progressBar.visibility = View.GONE
                    Log.d(TAG, "Failed to set value to database: ${it.message}")
                }
    }

    fun SplitString(str: String): String {
        var split = str.split("@")
        return split[0]
    }

    private fun refreshTokens(): String? {
        val newToken = FirebaseInstanceId.getInstance().token
        Log.d("newToken", (newToken))
        Toast.makeText(this, "Please fill out $newToken", Toast.LENGTH_SHORT).show()


        if (newToken != null) {
            GlobalScope.launch(Dispatchers.IO) {
                MyFirebaseMessagingService().saveTokenToFirebaseDatabase(newToken)
            }
        }
        return newToken
    }


}
