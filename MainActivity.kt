package com.satoshi.benchmark

import android.app.AlertDialog
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.params.MainNetParams
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.NumberFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {

    private lateinit var editTargetAddress: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvKeysPerSec: TextView
    private lateinit var tvTotalKeys: TextView
    private lateinit var tvConsole: TextView
    private lateinit var scrollConsole: ScrollView

    private val isRunning = AtomicBoolean(false)
    private val totalKeys = AtomicLong(0)
    private val keysThisSecond = AtomicLong(0)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val numberFormat = NumberFormat.getNumberInstance()

    // bitcoinj network parameters (mainnet for real address format)
    private val networkParams = MainNetParams.get()

    // HashSet loaded from assets/targets.txt — O(1) lookup per key
    private val targetHashSet = HashSet<String>()

    // Background benchmark thread
    private var benchmarkThread: Thread? = null

    // UI update loop — fires every 1 second
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            if (isRunning.get()) {
                val kps = keysThisSecond.getAndSet(0)
                val total = totalKeys.get()

                tvKeysPerSec.text = numberFormat.format(kps)
                tvTotalKeys.text = numberFormat.format(total)

                appendConsole("[STAT] ${numberFormat.format(kps)} keys/sec | total: ${numberFormat.format(total)}")

                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editTargetAddress = findViewById(R.id.editTargetAddress)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvKeysPerSec = findViewById(R.id.tvKeysPerSec)
        tvTotalKeys = findViewById(R.id.tvTotalKeys)
        tvConsole = findViewById(R.id.tvConsole)
        scrollConsole = findViewById(R.id.scrollConsole)

        btnStart.setOnClickListener { startBenchmark() }
        btnStop.setOnClickListener { stopBenchmark() }

        appendConsole("[INFO] BitcoinJ version: 0.16.2")
        appendConsole("[INFO] Network: Bitcoin Mainnet")
        appendConsole("[INFO] Key type: secp256k1 ECKey (random)")
        appendConsole("[INFO] Address format: Legacy (P2PKH / 1xxx)")

        // Load targets.txt from assets into the HashSet
        loadTargetsFromAssets()

        appendConsole("[INFO] Ready. Enter optional target address and press START.")
    }

    /**
     * Reads assets/targets.txt line by line.
     * Skips blank lines and lines starting with '#'.
     * Populates [targetHashSet] for O(1) lookups during the benchmark loop.
     */
    private fun loadTargetsFromAssets() {
        try {
            val inputStream = assets.open("targets.txt")
            val reader = BufferedReader(InputStreamReader(inputStream))
            var count = 0

            reader.useLines { lines ->
                lines.forEach { raw ->
                    val line = raw.trim()
                    if (line.isNotEmpty() && !line.startsWith("#")) {
                        targetHashSet.add(line)
                        count++
                    }
                }
            }

            appendConsole("[TARGETS] Loaded $count address(es) from targets.txt into HashSet")
        } catch (e: Exception) {
            appendConsole("[WARN] Could not load targets.txt: ${e.message}")
        }
    }

    private fun startBenchmark() {
        if (isRunning.get()) return

        val manualTarget = editTargetAddress.text.toString().trim()

        isRunning.set(true)
        totalKeys.set(0)
        keysThisSecond.set(0)

        btnStart.isEnabled = false
        btnStop.isEnabled = true
        tvKeysPerSec.text = "0"
        tvTotalKeys.text = "0"

        appendConsole("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendConsole("[START] Benchmark initiated")

        if (manualTarget.isNotEmpty()) {
            appendConsole("[TARGET] Manual target: $manualTarget")
        }
        val setSize = targetHashSet.size + (if (manualTarget.isNotEmpty()) 1 else 0)
        appendConsole("[TARGET] Checking against $setSize address(es) total")
        appendConsole("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        // Start the 1-second UI update ticker
        mainHandler.postDelayed(uiUpdateRunnable, 1000)

        // Launch benchmark on a background thread
        benchmarkThread = Thread {
            runBenchmarkLoop(manualTarget)
        }.also {
            it.name = "ECKey-Benchmark"
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopBenchmark() {
        isRunning.set(false)
        mainHandler.removeCallbacks(uiUpdateRunnable)
        benchmarkThread?.interrupt()

        val total = totalKeys.get()

        mainHandler.post {
            btnStart.isEnabled = true
            btnStop.isEnabled = false
            appendConsole("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendConsole("[STOP] Benchmark stopped")
            appendConsole("[RESULT] Total keys generated: ${numberFormat.format(total)}")
            appendConsole("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }
    }

    /**
     * Core benchmark loop — runs on a background thread.
     *
     * For every generated ECKey:
     *   1. Increments both the per-second counter and total counter (keys/sec always works).
     *   2. Checks the derived address against [targetHashSet] (O(1)).
     *   3. Also checks against the manual target from the EditText, if provided.
     *   4. On any match, stops the loop and hands off to [handleMatch] on the main thread.
     */
    private fun runBenchmarkLoop(manualTarget: String) {
        val hasManualTarget = manualTarget.isNotEmpty()
        val hasHashSetTargets = targetHashSet.isNotEmpty()
        var localCount = 0L

        // Log a sample entry every N keys to show real activity without flooding the UI
        val logInterval = 5000L

        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                // Generate a fresh random ECKey (cryptographically secure random private key)
                val key = ECKey()

                // Derive the legacy (P2PKH) Bitcoin address from the compressed public key
                val address = LegacyAddress.fromKey(networkParams, key).toString()

                // Always increment counters first — keeps keys/sec accurate regardless of checks
                localCount++
                keysThisSecond.incrementAndGet()
                totalKeys.incrementAndGet()

                // Log a sampled entry to the console every logInterval keys
                if (localCount % logInterval == 0L) {
                    val wif = key.getPrivateKeyAsWiF(networkParams)
                    val snippet = address.take(12) + "..." + address.takeLast(6)
                    mainHandler.post {
                        appendConsole("[SAMPLE] addr=$snippet | wif=${wif.take(8)}...")
                    }
                }

                // --- Target matching ---
                // Check HashSet first (O(1), covers assets/targets.txt entries)
                val inHashSet = hasHashSetTargets && targetHashSet.contains(address)
                // Also check the manual EditText target
                val matchesManual = hasManualTarget && address == manualTarget

                if (inHashSet || matchesManual) {
                    val wif = key.getPrivateKeyAsWiF(networkParams)
                    val hexPriv = key.privateKeyAsHex
                    val source = when {
                        inHashSet && matchesManual -> "HashSet + Manual"
                        inHashSet -> "HashSet (targets.txt)"
                        else -> "Manual Target"
                    }

                    // Stop the loop immediately
                    isRunning.set(false)

                    mainHandler.post {
                        handleMatch(address, wif, hexPriv, source)
                    }
                    return
                }

            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                mainHandler.post {
                    appendConsole("[ERROR] ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    /**
     * Called on the main thread when a generated address matches any target.
     * Plays the system notification sound and shows an AlertDialog with the WIF key.
     */
    private fun handleMatch(address: String, wif: String, hexPrivKey: String, source: String) {
        appendConsole("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendConsole("[MATCH!] Address match found! (source: $source)")
        appendConsole("[MATCH!] Address : $address")
        appendConsole("[MATCH!] WIF     : $wif")
        appendConsole("[MATCH!] PrivHex : $hexPrivKey")
        appendConsole("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        mainHandler.removeCallbacks(uiUpdateRunnable)
        btnStart.isEnabled = true
        btnStop.isEnabled = false

        // Play system notification sound
        playNotificationSound()

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle("⚡ Address Match Found")
            .setMessage(
                "Source: $source\n\n" +
                "Matched Address:\n$address\n\n" +
                "WIF Private Key:\n$wif\n\n" +
                "Hex Private Key:\n$hexPrivKey\n\n" +
                "Total keys searched: ${numberFormat.format(totalKeys.get())}"
            )
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .setCancelable(false)
            .show()
    }

    /**
     * Plays the device's default notification sound using [RingtoneManager].
     * Fails silently if audio is unavailable (e.g., silent mode or restricted profile).
     */
    private fun playNotificationSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            ringtone?.play()
        } catch (e: Exception) {
            appendConsole("[WARN] Could not play notification sound: ${e.message}")
        }
    }

    /**
     * Append a line to the console TextView and auto-scroll to bottom.
     * Must be called from the main thread.
     */
    private fun appendConsole(line: String) {
        val current = tvConsole.text.toString()
        val lines = current.split("\n")

        // Keep at most 200 lines to avoid unbounded memory growth
        val trimmed = if (lines.size > 200) {
            lines.takeLast(200).joinToString("\n")
        } else {
            current
        }

        tvConsole.text = "$trimmed\n$line"

        // Auto-scroll to bottom
        scrollConsole.post {
            scrollConsole.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        benchmarkThread?.interrupt()
    }
}
