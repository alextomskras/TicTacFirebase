package com.dreamer.tictacfirebase

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import com.dreamer.tictacfirebase.model.OnlineUser
import com.dreamer.tictacfirebase.repository.GameRepository
import com.dreamer.tictacfirebase.utils.AppConstants
import com.dreamer.tictacfirebase.utils.splitEmailFull
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Экран «Соседи»: список всех зарегистрированных игроков с индикатором онлайна
 * (по узлу presence/<username>) и кнопкой быстрого приглашения на партию.
 *
 * Данные берутся напрямую из GameRepository.observeUsers() — realtime-подписка
 * на users/ + актуальное состояние presence/. Обновление списка — по событию БД
 * и вручную через SwipeRefreshLayout.
 *
 * Результат приглашения возвращается в MainActivity через
 * AppConstants.KEY_INVITE_EMAIL — там запрос уходит в игру как обычный Request.
 */
class OnlineUsersActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OnlineUsersActivity"
    }

    private var myEmail: String? = null
    private lateinit var repository: GameRepository
    private lateinit var adapter: UsersAdapter

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyContainer: View
    private lateinit var emptyText: TextView

    private var usersJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Классическое поведение как в MainActivity: контент не заезжает под статус-бар
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_online_users)

        supportActionBar?.title = getString(R.string.neighbors_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        repository = GameRepository()

        myEmail = intent.getStringExtra(AppConstants.KEY_EMAIL)

        recyclerView = findViewById(R.id.rvUsers)
        swipeRefresh = findViewById(R.id.swipeRefreshUsers)
        progressBar = findViewById(R.id.progressBarUsers)
        emptyContainer = findViewById(R.id.emptyStateContainer)
        emptyText = findViewById(R.id.tvEmptyState)

        adapter = UsersAdapter(
            onClick = { user -> inviteUser(user) },
            isSelf = { user -> user.email.equals(myEmail, ignoreCase = true) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        swipeRefresh.setColorSchemeResources(R.color.colorPrimary)
        swipeRefresh.setOnRefreshListener { refreshList() }

        subscribeToUsers()
    }

    /** Pull-to-refresh: обновляем своё presence и переподписываемся на список */
    private fun refreshList() {
        usersJob?.cancel()
        usersJob = lifecycleScope.launch {
            try {
                myEmail?.let { repository.refreshPresenceOnce(it) }
            } catch (e: Exception) {
                Toast.makeText(
                    this@OnlineUsersActivity,
                    getString(R.string.error_loading_neighbors, e.message),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                swipeRefresh.isRefreshing = false
            }
            subscribeToUsers()
        }
    }

    /**
     * Подписка на список соседей: онлайн-игроки наверху, далее по алфавиту.
     */
    private fun subscribeToUsers() {
        usersJob?.cancel()
        usersJob = lifecycleScope.launch {
            showLoading(true)
            try {
                repository.observeUsers().collect { users ->
                    val sorted = users.sortedWith(
                        compareByDescending<OnlineUser> { it.isOnline }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.username }
                    )
                    adapter.submitList(sorted)
                    showLoading(false)
                    swipeRefresh.isRefreshing = false
                    emptyContainer.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
                    emptyText.text = getString(R.string.no_neighbors_yet)
                }
            } catch (e: Exception) {
                showLoading(false)
                swipeRefresh.isRefreshing = false
                Toast.makeText(
                    this@OnlineUsersActivity,
                    getString(R.string.error_loading_neighbors, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Приглашение соседа на игру: возвращаемся в MainActivity с его email —
     * там запрос отправляется через GameViewModel (UiEvent.SendGameRequest).
     */
    private fun inviteUser(user: OnlineUser) {
        if (user.email.equals(myEmail, ignoreCase = true)) return
        val resultIntent = Intent().putExtra(AppConstants.KEY_INVITE_EMAIL, user.email)
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show && adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    override fun onSupportNavigateUp(): Boolean {
        @Suppress("DEPRECATION")
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        usersJob?.cancel()
    }

    /**
     * Адаптер списка соседей: DiffUtil без пересоздания всего списка,
     * аватар через Coil, зелёная точка — онлайн, серая — офлайн.
     */
    class UsersAdapter(
        private val onClick: (OnlineUser) -> Unit,
        private val isSelf: (OnlineUser) -> Boolean
    ) : ListAdapter<OnlineUser, UsersAdapter.UserViewHolder>(DIFF) {

        class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val avatar: CircleImageView = view.findViewById(R.id.imageViewUserAvatar)
            val onlineDot: View = view.findViewById(R.id.viewOnlineDot)
            val name: TextView = view.findViewById(R.id.tvUserName)
            val status: TextView = view.findViewById(R.id.tvUserStatus)
            val inviteButton: Button = view.findViewById(R.id.buInviteUser)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
            val item = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_online_user, parent, false)
            return UserViewHolder(item)
        }

        override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
            val user = getItem(position)
            val context = holder.itemView.context

            holder.name.text = splitEmailFull(user.email)

            if (isSelf(user)) {
                holder.status.text = context.getString(R.string.you_label)
                holder.inviteButton.visibility = View.GONE
            } else {
                holder.inviteButton.visibility = View.VISIBLE
                holder.inviteButton.isEnabled = user.isOnline
                holder.status.text = if (user.isOnline) {
                    context.getString(R.string.online_label)
                } else {
                    context.getString(R.string.offline_label)
                }
            }

            // Индикатор присутствия: зелёная точка только когда сосед онлайн
            holder.onlineDot.setBackgroundResource(
                if (user.isOnline) R.drawable.dot_online else R.drawable.dot_offline
            )

            if (!user.profileImageUrl.isNullOrEmpty()) {
                holder.avatar.load(user.profileImageUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_fire_emoji)
                    error(R.drawable.ic_fire_emoji)
                }
            } else {
                holder.avatar.setImageResource(R.drawable.ic_fire_emoji)
            }

            holder.inviteButton.setOnClickListener { onClick(user) }
            holder.itemView.setOnClickListener {
                if (!isSelf(user) && user.isOnline) onClick(user)
            }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<OnlineUser>() {
                override fun areItemsTheSame(oldItem: OnlineUser, newItem: OnlineUser) =
                    oldItem.username == newItem.username

                override fun areContentsTheSame(oldItem: OnlineUser, newItem: OnlineUser) =
                    oldItem == newItem
            }
        }
    }
}

