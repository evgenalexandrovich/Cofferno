package com.coffee.telemetry

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

// Модели данных
data class TelemetryData(
    var revenue: Int = 0,
    var cups: Int = 0,
    var error: String = "Нет ошибок",
    var coffeePrice: Int = 150
)

data class Command(
    val action: String, // "RESET_ERROR", "SET_PRICE"
    val value: Int = 0
)

class MainActivity : AppCompatActivity() {

    private lateinit var mqttClient: MqttClient
    private val gson = Gson()
    private val topicTelemetry = "coffee/demo_machine_01/telemetry"
    private val topicCommands = "coffee/demo_machine_01/commands"

    private var currentData = TelemetryData()
    private var isMachineMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showRoleSelectionScreen()
    }

    // Экран выбора роли
    private fun showRoleSelectionScreen() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 100, 50, 50)
        }

        val title = TextView(this).apply {
            text = "COFFEE TELEMETRY\nВыберите режим работы устройства:"
            textSize = 20f
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }

        val btnMachine = Button(this).apply {
            text = "Режим: Кофемашина"
            setOnClickListener { setupMachineMode() }
        }

        val btnOperator = Button(this).apply {
            text = "Режим: Оператор (Смартфон)"
            setOnClickListener { setupOperatorMode() }
        }

        layout.addView(title)
        layout.addView(btnMachine)
        layout.addView(btnOperator)
        setContentView(layout)
    }

    // --- РЕЖИМ КОФЕМАШИНЫ ---
    private fun setupMachineMode() {
        isMachineMode = true
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }

        val statusText = TextView(this).apply { text = "СТАТУС: КОФЕМАШИНА ОНЛАЙН\n"; textSize = 18f; setTextColor(Color.GREEN) }
        val infoText = TextView(this).apply { textSize = 16f }
        
        fun updateUI() {
            infoText.text = "Выручка: ${currentData.revenue} ₽\nПродано чашек: ${currentData.cups}\nЦена кофе: ${currentData.coffeePrice} ₽\nОшибка: ${currentData.error}\n"
        }
        updateUI()

        // Кнопки для симуляции событий
        val btnSell = Button(this).apply {
            text = "Симуляция: Продать кофе"
            setOnClickListener {
                currentData.cups += 1
                currentData.revenue += currentData.coffeePrice
                updateUI()
                publishTelemetry()
            }
        }

        val btnTriggerError = Button(this).apply {
            text = "Симуляция: Ошибка (Нет зерен!)"
            setBackgroundColor(Color.RED)
            setTextColor(Color.WHITE)
            setOnClickListener {
                currentData.error = "E-01: Нет зерен кофе"
                updateUI()
                publishTelemetry()
            }
        }

        layout.addView(statusText)
        layout.addView(infoText)
        layout.addView(btnSell)
        layout.addView(btnTriggerError)
        setContentView(layout)

        initMqtt {
            // Слушаем команды от смартфона
            mqttClient.subscribe(topicCommands)
        }
    }

    // --- РЕЖИМ ОПЕРАТОРА (СМАРТФОН) ---
    private fun setupOperatorMode() {
        isMachineMode = false
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }

        val title = TextView(this).apply { text = "Управление кофемашиной #1\n"; textSize = 18f }
        val dataText = TextView(this).apply { text = "Ожидание данных от кофемашины..."; textSize = 16f }
        
        val btnReset = Button(this).apply {
            text = "Удаленно сбросить ошибку"
            setOnClickListener {
                sendCommand(Command(action = "RESET_ERROR"))
                Toast.makeText(this@MainActivity, "Команда отправлена", Toast.LENGTH_SHORT).show()
            }
        }

        val priceInput = EditText(this).apply {
            hint = "Новая цена кофе (₽)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val btnSetPrice = Button(this).apply {
            text = "Установить новую цену"
            setOnClickListener {
                val newPrice = priceInput.text.toString().toIntOrNull() ?: 150
                sendCommand(Command(action = "SET_PRICE", value = newPrice))
                Toast.makeText(this@MainActivity, "Цена изменена", Toast.LENGTH_SHORT).show()
            }
        }

        layout.addView(title)
        layout.addView(dataText)
        layout.addView(btnReset)
        layout.addView(priceInput)
        layout.addView(btnSetPrice)
        setContentView(layout)

        initMqtt {
            // Слушаем телеметрию от кофемашины
            mqttClient.subscribe(topicTelemetry)
            mqttClient.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {}
                override fun connectionLost(cause: Throwable?) {}
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val json = String(message?.payload ?: byteArrayOf())
                    val data = gson.fromJson(json, TelemetryData::class.java)
                    runOnUiThread {
                        dataText.text = "ДАННЫЕ В РЕАЛЬНОМ ВРЕМЕНИ:\n" +
                                "Выручка: ${data.revenue} ₽\n" +
                                "Чашек: ${data.cups}\n" +
                                "Текущая цена: ${data.coffeePrice} ₽\n" +
                                "Статус: ${data.error}\n"
                        if (data.error != "Нет ошибок") {
                            dataText.setTextColor(Color.RED)
                        } else {
                            dataText.setTextColor(Color.DKGRAY)
                        }
                    }
                }
            })
        }
    }

    // Инициализация MQTT брокера (HiveMQ Public)
    private fun initMqtt(onConnected: () -> Unit) {
        val brokerUri = "tcp://broker.hivemq.com:1883"
        val clientId = "Client_" + UUID.randomUUID().toString().substring(0, 8)
        mqttClient = MqttClient(brokerUri, clientId, MemoryPersistence())

        Thread {
            try {
                val options = MqttConnectOptions().apply { isAutomaticReconnect = true; isCleanSession = true }
                mqttClient.connect(options)
                runOnUiThread { onConnected() }

                if (isMachineMode) {
                    mqttClient.setCallback(object : MqttCallbackExtended {
                        override fun connectComplete(reconnect: Boolean, serverURI: String?) {}
                        override fun connectionLost(cause: Throwable?) {}
                        override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                        override fun messageArrived(topic: String?, message: MqttMessage?) {
                            val cmd = gson.fromJson(String(message?.payload ?: byteArrayOf()), Command::class.java)
                            runOnUiThread {
                                if (cmd.action == "RESET_ERROR") currentData.error = "Нет ошибок"
                                if (cmd.action == "SET_PRICE") currentData.coffeePrice = cmd.value
                                setupMachineMode()
                                publishTelemetry()
                            }
                        }
                    })
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun publishTelemetry() {
        Thread {
            try {
                val json = gson.toJson(currentData)
                val msg = MqttMessage(json.toByteArray()).apply { qos = 1 }
                mqttClient.publish(topicTelemetry, msg)
            } catch (e: Exception) { e.printStackTrace() }
        }.start()
    }

    private fun sendCommand(cmd: Command) {
        Thread {
            try {
                val json = gson.toJson(cmd)
                val msg = MqttMessage(json.toByteArray()).apply { qos = 1 }
                mqttClient.publish(topicCommands, msg)
            } catch (e: Exception) { e.printStackTrace() }
        }.start()
    }
}
