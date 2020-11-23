package com.dreamer.tictacfirebase


import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dreamer.tictacfirebase.models.User
import com.dreamer.tictacfirebase.service.MyFirebaseMessagingService
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.iid.FirebaseInstanceId
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import com.squareup.picasso.Picasso
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*
import kotlin.collections.ArrayList


open class MainActivity : AppCompatActivity() {

    companion object {
        val TAG = "MainActivity"
    }

    private val s = "793202519353"
    private val SENDER_ID = s

    //            getString(R.string.SENDER_ID)
    private val random = Random()

    //database instance
    private var database = FirebaseDatabase.getInstance()
    private var myRef = database.reference

    var myEmail: String? = null


    lateinit var tokenID: MyFirebaseMessagingService
    lateinit var mFirebaseAnalytics: FirebaseAnalytics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        //Hide img+player2name
        player2_text_View!!.visibility = View.GONE
        image_View_user2!!.visibility = View.GONE

        //Block_ACCEPT_BUTTON
        buAcceptEvent.isEnabled = false

        refreshTokens()

//        var tokenID = MyFirebaseMessagingService()
//        val newToken = tokenID.otherStyleGetToken().toString()
//        Log.d(TAG, "getTokenID: $newToken")


        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this)

        val channelId = getString(R.string.default_notification_channel_id)
        val b: Bundle? = intent.extras
        myEmail = b?.getString("email")
        Log.d(TAG, "getExtraEmail: $myEmail")
        supportActionBar?.title = getString(R.string.app_name) + " $myEmail"
        IncommingCalls()
    }

    private fun refreshTokens(): String? {
        val newToken = FirebaseInstanceId.getInstance().token
        Log.d("newToken", (newToken))
//        Toast.makeText(this, "Please fill out $newToken", Toast.LENGTH_SHORT).show()
        return newToken

//        if (newToken != null) {
//            MyFirebaseMessagingService().saveTokenToFirebaseDatabase(newToken)
//        }
    }

    fun buClick(view: View) {
        val buSelected = view as Button
        var cellID = 0
        when (buSelected.id) {
            R.id.bu1 -> cellID = 1
            R.id.bu2 -> cellID = 2
            R.id.bu3 -> cellID = 3
            R.id.bu4 -> cellID = 4
            R.id.bu5 -> cellID = 5
            R.id.bu6 -> cellID = 6
            R.id.bu7 -> cellID = 7
            R.id.bu8 -> cellID = 8
            R.id.bu9 -> cellID = 9

        }
        Toast.makeText(this, "ID:" + cellID, Toast.LENGTH_LONG).show()

        sessionID?.let { myRef.child("PlayerOnline").child(it).child(cellID.toString()).setValue(myEmail) }
//        myRef.child("PlayerOnline").child(sessionID!!).child(cellID.toString()).setValue(myEmail)
    }

    var player1 = ArrayList<Int>()
    var player2 = ArrayList<Int>()
    var ActivePlayer = 1


    fun PlayGame(cellID: Int, buSelected: Button) {

        if (ActivePlayer == 1) {
            buSelected.text = "X"
            buSelected.setBackgroundResource(R.color.blue)
            player1.add(cellID)
            ActivePlayer = 2

        } else {
            buSelected.text = "O"
            buSelected.setBackgroundResource(R.color.darkgreen)
            player2.add(cellID)
            ActivePlayer = 1
        }


        buSelected.isEnabled = false
        CheckWiner()
    }


    fun CheckWiner() {
        var winer = -1

        // row 1
        if (player1.contains(1) && player1.contains(2) && player1.contains(3)) {
            winer = 1
        }
        if (player2.contains(1) && player2.contains(2) && player2.contains(3)) {
            winer = 2
        }


        // row 2
        if (player1.contains(4) && player1.contains(5) && player1.contains(6)) {
            winer = 1
        }
        if (player2.contains(4) && player2.contains(5) && player2.contains(6)) {
            winer = 2
        }


        // row 3
        if (player1.contains(7) && player1.contains(8) && player1.contains(9)) {
            winer = 1
        }
        if (player2.contains(7) && player2.contains(8) && player2.contains(9)) {
            winer = 2
        }


        // col 1
        if (player1.contains(1) && player1.contains(4) && player1.contains(7)) {
            winer = 1
        }
        if (player2.contains(1) && player2.contains(4) && player2.contains(7)) {
            winer = 2
        }


        // col 2
        if (player1.contains(2) && player1.contains(5) && player1.contains(8)) {
            winer = 1
        }
        if (player2.contains(2) && player2.contains(5) && player2.contains(8)) {
            winer = 2
        }


        // col 3
        if (player1.contains(3) && player1.contains(6) && player1.contains(9)) {
            winer = 1
        }
        if (player2.contains(3) && player2.contains(6) && player2.contains(9)) {
            winer = 2
        }

        // cros 1
        if (player1.contains(1) && player1.contains(5) && player1.contains(9)) {
            winer = 1
        }
        if (player2.contains(1) && player2.contains(5) && player2.contains(9)) {
            winer = 2
        }

        // cros 2
        if (player1.contains(3) && player1.contains(5) && player1.contains(7)) {
            winer = 1
        }
        if (player2.contains(3) && player2.contains(5) && player2.contains(7)) {
            winer = 2
        }


        if (winer != -1) {

            if (winer == 1) {
                android.widget.Toast.makeText(this, " Player 1  win the game", android.widget.Toast.LENGTH_LONG).show()
                restartGame()
            } else {
                android.widget.Toast.makeText(this, " Player 2  win the game", android.widget.Toast.LENGTH_LONG).show()
                restartGame()
            }

        }

    }

    fun AutoPlay(cellID: Int) {


        val buSelect: Button? = when (cellID) {
            1 -> bu1
            2 -> bu2
            3 -> bu3
            4 -> bu4
            5 -> bu5
            6 -> bu6
            7 -> bu7
            8 -> bu8
            9 -> bu9
            else -> {
                bu1
            }
        }

        buSelect?.let { PlayGame(cellID, it) }

    }
//    lateinit var ImageProfile = getImageProfile().toString()


    fun buRequestEvent(view: View) {
        GlobalScope.launch(Dispatchers.Main) {

            val userUID = FirebaseAuth.getInstance().uid.toString()
            var userDemail = etEmail.text.toString()

            //unHide player2 icon
            player2_text_View!!.visibility = View.VISIBLE
            image_View_user2.visibility = View.VISIBLE
            player2_text_View.text = "Player2-" + SplitString(userDemail)


            val pict1 = GlobalScope.launch(Dispatchers.IO) {
                getImageProfile {
                    Log.e(TAG, "PlayerPictIT1:" + it)
                    val pict1 = it
                    Picasso.get().load(pict1)
                            .into(image_View_user2)
                }
            }

            Log.e(TAG, "PlayerPict1: $pict1")

            myRef.child("users").child(SplitString(userDemail)).child("request").push().setValue(myEmail)
            myRef.child("latest-messages").child(userUID).push().child(SplitString(userDemail)).child("request").push()
                    .setValue(myEmail)


            PlayerOnline(SplitString(myEmail!!) + SplitString(userDemail)) // husseinjena
            PlayerSymbol = "X"
        }
    }


    fun buAcceptEvent(view: View) {
        var userDemail = etEmail.text.toString()
        myRef.child("users").child(SplitString(userDemail)).child("request").push().setValue(myEmail)

        //unHide player2 icon
        player2_text_View!!.visibility = View.VISIBLE
        image_View_user2.visibility = View.VISIBLE

        player2_text_View.text = "Player2-" + SplitString(userDemail)

        PlayerOnline(SplitString(userDemail) + SplitString(myEmail!!)) //husseinjena
        PlayerSymbol = "O"

    }


    //var cellID: String? =
    var sessionID: String? = null
    var PlayerSymbol: String? = null

    fun PlayerOnline(sessionID: String) {
        this.sessionID = sessionID
        Log.d(TAG, "PlayerOn0: $sessionID")

        getPlayerOnline(sessionID) {
            var trace1 = it
            Log.d(TAG, "PlayerOngetPlayerOnline: $it")
        }
        myRef.child("PlayerOnline").removeValue()
        myRef.child("PlayerOnline").child(sessionID)
//        myRef.child("PlayerOnline")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(dataSnapshot: DataSnapshot) {

                        try {
                            player1.clear()
                            player2.clear()
                            val KeyName = dataSnapshot.value.toString()
                            Log.d(TAG, "KeyName_PlayerOn3!!!!: $KeyName")
                            var PlayerOnlineProfile = dataSnapshot.value.toString().trim()
                            Log.d(TAG, "PlayerOn3!!!!: $PlayerOnlineProfile")


//
//                      val td=dataSnapshot!!.value as HashMap<String,Any>
                            val td = (if (dataSnapshot != null) dataSnapshot.value else null) as? HashMap<*, *>
//
                            Log.d(TAG, "PlayerOn1: $td")
//
//                        if (td != null) {
//
//                            var value: String?
//                            for (key in td.keys) {
//                                value = td[key] as String
//                                Log.d(TAG, "PlayerHash: $value")
//
//                                if (value != myEmail) {
//                                    ActivePlayer = if (PlayerSymbol === "X") 1 else 2
//                                    Log.d(TAG, "PlayerSymbolValue: $ActivePlayer")
//                                } else {
//                                    ActivePlayer = if (PlayerSymbol === "X") 2 else 1
//                                    Log.d(TAG, "PlayerSymbolElseValue: $ActivePlayer")
//                                }
//                                Log.d(TAG, "PlayerKey: $key")
//
//                                AutoPlay(key.toString().toInt())
//
//
//                            }
//
//                        }

                        } catch (ex: Exception) {
                            println("Somthing wrongex" + ex)
                            Toast.makeText(applicationContext, " Somthing wrongex+$ex", Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onCancelled(p0: DatabaseError) {

                    }

                })


    }

    //    function: (String) -> Unit): String
    fun getPlayerOnline(sessionID: String, function: (String) -> Unit) {
        this.sessionID = sessionID
        myRef.child("PlayerOnline").removeValue()
        myRef.child("PlayerOnline").child(sessionID)
//        myRef.child("PlayerOnline")
                .addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(dataSnapshot: DataSnapshot) {
                        try {
                            player1.clear()
                            player2.clear()
                            val children = dataSnapshot.children
                            children.forEach {
                                println(it.toString())
                                Log.d(TAG, "KeyName_PlayerOn4!!!!: " + it.toString())
                                if (it != null) {
                                    Log.d(TAG, "KeyName_PlayerOn5!!!!: " + it.key.toString())
                                    Log.d(TAG, "KeyName_PlayerOn6!!!!: " + it.value.toString())
                                    if (it.value.toString() != myEmail) {
                                        ActivePlayer = if (PlayerSymbol === "X") 1 else 2
                                        Log.d(TAG, "PlayerSymbolValue: $ActivePlayer")
                                    } else {
                                        ActivePlayer = if (PlayerSymbol === "X") 2 else 1
                                        Log.d(TAG, "PlayerSymbolElseValue: $ActivePlayer")
                                    }
                                    Log.d(TAG, "PlayerKey: " + it.key.toString())

                                    AutoPlay(it.key!!.toInt())

                                }
                            }

//                        val KeyName = dataSnapshot.key.toString()
//                        Log.d(TAG, "KeyName_PlayerOn3!!!!: $KeyName")
//                        var PlayerOnlineProfile = dataSnapshot.value.toString().trim()
//                        Log.d(TAG, "PlayerOn3!!!!: $PlayerOnlineProfile")
////
////
//                        val td = (if (dataSnapshot != null) dataSnapshot.value else null) as? HashMap<*, *>
//
////                            Log.d(TAG, "PlayerOn1: $td")
////                            function(KeyName)
////                            Log.d(TAG, "PlayerOn1: $KeyName")
//
//                        if (td != null) {
//
//                            var value: String?
//                            for (key in td.keys) {
//                                value = td[key] as String
//                                Log.d(TAG, "PlayerHash: $value")
//
//                                if (value != myEmail) {
//                                    ActivePlayer = if (PlayerSymbol === "X") 1 else 2
//                                    Log.d(TAG, "PlayerSymbolValue: $ActivePlayer")
//                                } else {
//                                    ActivePlayer = if (PlayerSymbol === "X") 2 else 1
//                                    Log.d(TAG, "PlayerSymbolElseValue: $ActivePlayer")
//                                }
//                                Log.d(TAG, "PlayerKey: $key")
//
//                                AutoPlay(key.toString().toInt())
//
//
//                            }
//
//                        }

                        } catch (ex: Exception) {
                            println("getPlayerOnline error " + ex)
                            Toast.makeText(applicationContext, " getPlayerOnline error $ex", Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onCancelled(p0: DatabaseError) {
                        println(p0.message.toString())

                    }


                })


    }


    var number = 0

    fun IncommingCalls() {
        GlobalScope.launch(Dispatchers.Main) {
            myRef.child("latest-messages").removeValue()
            myRef.child("users").child(SplitString(myEmail!!)).child("request")


                    .addValueEventListener(object : ValueEventListener {


                        override fun onDataChange(p0: DataSnapshot) {


                            try {
                                val td: HashMap<String, Any>
                                td = p0.value as HashMap<String, Any>
                                if (td != null) {

                                    var value: String
                                    for (key in td.keys) {
                                        value = td[key] as String
                                        Log.d(TAG, "Incomming: $value")
                                        etEmail.setText(value)
///                                val notifyme = Notifications()
//                                val notifyme = MyFirebaseMessagingService()
///                                notifyme.Notify(applicationContext, value + " want to play tic tac toy", number)
//                                notifyme.sendNotification(RemoteMessage())
                                        perfotmFCMSendMessages()
                                        //Release_ACCEPT_BUTTON
                                        buAcceptEvent.isEnabled = true
                                        number++
                                        Log.d(TAG, "Incomming_myEmail: $myEmail")
                                        Toast.makeText(applicationContext, "Incomming_myEmail: $myEmail", Toast.LENGTH_LONG).show()
                                        myRef.child("users").child(SplitString(myEmail!!)).child("request")
                                                .setValue(true)

                                        break

                                    }

                                }

                            } catch (ex: Exception) {
                                println("Somthing EXwr!!!!!_: " + ex)
                                Toast.makeText(applicationContext, "Somthing EXwr!!!!!_: $ex", Toast.LENGTH_LONG).show()
                            }
                        }

                        override fun onCancelled(p0: DatabaseError) {

                        }


                    })

        }
    }

    //    fun readData(myCallback: (List<String>) -> Unit)
    fun getImageProfile(function: (String) -> Unit): String {
        var test1 = ""
        GlobalScope.launch(Dispatchers.Main) {


            var imgProfile = ""
            var userDemail = etEmail.text.toString()
            var splituserDemail = SplitString(userDemail)
            Log.e(TAG, "UpstreamSplituserDemail: " + splituserDemail)

            val ref = FirebaseDatabase.getInstance().getReference("/users/$splituserDemail")

            //        databaseReference = FirebaseDatabase.getInstance().reference.child("Users").child(user_id)

            ref.addValueEventListener(object : ValueEventListener {

                override fun onDataChange(dataSnapshot: DataSnapshot) {
                    try {
                        val UserName = dataSnapshot.child("name").value as String?
                        //                val ImageProfile = dataSnapshot.child("profileImageUrl").value as String?
                        var ImageProfile = dataSnapshot.getValue(User::class.java)
                        var test1 = ImageProfile!!.profileImageUrl



                        Log.e(TAG, "UpstreamImageProfile: " + imgProfile)
                        Log.e(TAG, "Upstream-test1: " + test1)
                        function(test1)


                    } catch (ex: Exception) {
                        println("Somthing EXwr" + ex)
                        Toast.makeText(applicationContext, " Somthing EXwr+$ex", Toast.LENGTH_LONG).show()
                    }
                    //


                }

                override fun onCancelled(databaseError: DatabaseError) {

                }

            })
            Log.e(TAG, "Upstream-Return-test1: " + test1)


        }
        return test1
    }


    fun SplitString(str: String): String {
        var split = str.split("@")
        return split[0]
    }

    fun perfotmFCMSendMessages() {
        val fromId = FirebaseAuth.getInstance().uid
//        val user = intent.getParcelableExtra<User>(NewMessageActivity.USER_KEY)
        val user = myEmail
        val toId = user
//        btn_upmessage.setOnClickListener {
        val fm = FirebaseMessaging.getInstance()
//793202519353@gcm.googleapis.com
        val message = RemoteMessage.Builder(SENDER_ID + "@fcm.googleapis.com")

                .setMessageId(Integer.toString(random.nextInt(9999)))
                .addData("TEST1-- $fromId", "TEST1--  $toId")
//                    .addData(edt_key1.text.toString(), edt_value1.text.toString())
//                    .addData(edt_key2.text.toString(), edt_value2.text.toString())
                .build()
        Log.e(TAG, "UpstreamData: " + message)

        if (!message.data.isEmpty()) {
            Log.e(TAG, "UpstreamData: " + message.data)
        }

        if (!message.messageId!!.isEmpty()) {
            Log.e(TAG, "UpstreamMessageId: " + message.messageId)
        }

        fm.send(message)
//        }
    }

    fun restartGame() {
        Toast.makeText(this, " RESTART the game", Toast.LENGTH_LONG).show()
    }


}
