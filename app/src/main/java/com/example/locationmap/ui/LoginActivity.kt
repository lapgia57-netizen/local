package com.example.locationmap.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.locationmap.R
import com.example.locationmap.data.FirebaseRepository
import com.example.locationmap.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val repo = FirebaseRepository()
    private var isRegisterMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityLoginBinding.inflate(layoutInflater)
            setContentView(binding.root)

            if (repo.currentUser != null) {
                goToMain()
                return
            }
            setupListeners()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate failed", e)
            Toast.makeText(this, "Lỗi khởi tạo: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupListeners() {
        binding.btnLogin.setOnClickListener {
            if (isRegisterMode) doRegister() else doLogin()
        }
        binding.btnToggleMode.setOnClickListener {
            isRegisterMode = !isRegisterMode
            updateUI()
        }
    }

    private fun updateUI() {
        if (isRegisterMode) {
            binding.tilConfirmPassword.visibility = View.VISIBLE
            binding.btnLogin.text = getString(R.string.register)
            binding.btnToggleMode.text = "Đã có tài khoản? Đăng nhập"
        } else {
            binding.tilConfirmPassword.visibility = View.GONE
            binding.btnLogin.text = getString(R.string.login)
            binding.btnToggleMode.text = "Chưa có tài khoản? Đăng ký"
        }
        binding.tilEmail.error = null
        binding.tilPassword.error = null
        binding.tilConfirmPassword.error = null
    }

    private fun doLogin() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        if (!validate(email, password)) return

        showLoading(true)
        lifecycleScope.launch {
            val result = repo.login(email, password)
            showLoading(false)
            result.onSuccess { goToMain() }
                .onFailure {
                    val msg = when {
                        it.message?.contains("password", true) == true -> "Sai mật khẩu"
                        it.message?.contains("user", true) == true -> "Email chưa đăng ký"
                        it.message?.contains("network", true) == true -> "Không có mạng"
                        else -> "Đăng nhập thất bại: ${it.message}"
                    }
                    Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun doRegister() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val confirm = binding.etConfirmPassword.text.toString()

        if (!validate(email, password)) return
        if (password != confirm) {
            binding.tilConfirmPassword.error = getString(R.string.password_mismatch)
            return
        }

        showLoading(true)
        lifecycleScope.launch {
            val result = repo.register(email, password)
            showLoading(false)
            result.onSuccess {
                Toast.makeText(this@LoginActivity, getString(R.string.register_success), Toast.LENGTH_LONG).show()
                isRegisterMode = false
                updateUI()
                binding.etPassword.setText("")
                binding.etConfirmPassword.setText("")
            }.onFailure {
                val msg = when {
                    it.message?.contains("already", true) == true -> "Email đã được sử dụng"
                    it.message?.contains("network", true) == true -> "Không có mạng"
                    else -> "Đăng ký thất bại: ${it.message}"
                }
                Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun validate(email: String, password: String): Boolean {
        binding.tilEmail.error = null
        binding.tilPassword.error = null
        binding.tilConfirmPassword.error = null

        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = getString(R.string.invalid_email)
            return false
        }
        if (password.length < 6) {
            binding.tilPassword.error = getString(R.string.weak_password)
            return false
        }
        return true
    }

    private fun showLoading(show: Boolean) {
        try {
            binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
            binding.btnLogin.isEnabled = !show
            binding.btnToggleMode.isEnabled = !show
        } catch (_: Exception) {}
    }

    private fun goToMain() {
        try {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "goToMain failed", e)
        }
    }

    companion object {
        private const val TAG = "LoginActivity"
    }
}
