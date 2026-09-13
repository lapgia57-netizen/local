package com.example.locationmap.ui

import android.content.Intent
import android.os.Bundle
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
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Auto login if already signed in
        if (repo.currentUser != null) {
            goToMain()
            return
        }

        setupListeners()
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
    }

    private fun doLogin() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()

        if (!validate(email, password)) return

        showLoading(true)
        lifecycleScope.launch {
            val result = repo.login(email, password)
            showLoading(false)
            result.onSuccess {
                goToMain()
            }.onFailure {
                Toast.makeText(
                    this@LoginActivity,
                    "${getString(R.string.login_failed)}: ${it.message}",
                    Toast.LENGTH_LONG
                ).show()
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
                Toast.makeText(
                    this@LoginActivity,
                    getString(R.string.register_success),
                    Toast.LENGTH_LONG
                ).show()
                isRegisterMode = false
                updateUI()
                binding.etPassword.setText("")
                binding.etConfirmPassword.setText("")
            }.onFailure {
                Toast.makeText(
                    this@LoginActivity,
                    "${getString(R.string.error_occurred)}: ${it.message}",
                    Toast.LENGTH_LONG
                ).show()
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
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !show
        binding.btnToggleMode.isEnabled = !show
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
