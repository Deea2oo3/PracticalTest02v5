package ro.pub.cs.systems.eim.practicaltest02v5;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;

public class PracticalTest02v5 extends AppCompatActivity {

    // Tag folosit pentru a filtra mesajele în Logcat (poti scrie "PT02v1" in search-ul din Android Studio)
    private static final String TAG = "PT02v1";

    // Componente de UI (Exercițiul 2)
    private EditText portEditText;     // Câmpul unde scrii portul (ex: 8080)
    private EditText cityEditText;   // Câmpul unde scrii orasul
    private Button startServerButton, stopServerButton, requestButton;
    private TextView resultTextView;   // Unde se vor afișa rezultatele primite de la server

    // Referință către thread-ul principal de server
    private ServerThread serverThread;

    // Handler pentru a trimite comenzi către interfața grafică (UI) din thread-uri secundare
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Setează layout-ul XML
        setContentView(R.layout.activity_practical_test02v5_main);

        // Identificăm elementele din XML prin ID-urile lor (Ex. 2)
        portEditText = findViewById(R.id.portEditText);
        cityEditText = findViewById(R.id.cityEditText);
        startServerButton = findViewById(R.id.startServerButton);
        stopServerButton = findViewById(R.id.stopServerButton);
        requestButton = findViewById(R.id.requestButton);
        resultTextView = findViewById(R.id.resultTextView);

        // Click Listener pentru pornirea serverului
        startServerButton.setOnClickListener(v -> {
            int port = parsePort(); // Citim portul din EditText
            if (port != -1) {
                // Instanțiem și pornim thread-ul de server pe portul ales
                serverThread = new ServerThread(port);
                serverThread.start();
                toast("Server pornit pe port " + port);
            }
        });

        // Click Listener pentru oprirea serverului
        stopServerButton.setOnClickListener(v -> {
            stopServer();
            toast("Server oprit");
        });

        // Click Listener pentru butonul de cerere (Clientul trimite către Server)
        requestButton.setOnClickListener(v -> {
            int port = parsePort(); // Portul la care vrem să ne conectăm
            String city = cityEditText.getText().toString(); // Orasul trimis
            // Creăm un thread nou de client pentru a nu bloca interfața (Ex. 4)
            // 127.0.0.1 înseamnă "localhost" (adică telefonul se conectează la el însuși)
            new ClientThread("127.0.0.1", port, city).start();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopServer(); // Siguranță: oprim serverul când închidem aplicația
    }

    private void stopServer() {
        if (serverThread != null) {
            serverThread.stopServer();
            serverThread = null;
        }
    }

    // Metodă utilitară pentru a transforma textul din EditText în număr (Port)
    private int parsePort() {
        try {
            return Integer.parseInt(portEditText.getText().toString().trim());
        } catch (Exception e) {
            Log.w(TAG, "Eroare port invalid: " + e.getMessage());
            toast("Port invalid!");
            return -1;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // =========================================================================
    // SERVER: Rulează în fundal și așteaptă conexiuni (Exercițiul 3)
    // =========================================================================

    private class ServerThread extends Thread {
        private final int port;
        private volatile boolean running = true;
        private ServerSocket serverSocket; // Obiectul care "ascultă" portul

        private final HashMap<String, WeatherInfo> dataCache = new HashMap<>();

        // Metodă sincronizată pentru a accesa/modifica cache-ul din thread-uri diferite
        public synchronized void setData(String city, WeatherInfo info) {
            dataCache.put(city, info);
        }

        public synchronized WeatherInfo getData(String city) {
            return dataCache.get(city);
        }

        ServerThread(int port) {
            this.port = port;
        }

        void stopServer() {
            running = false;
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (Exception ignored) {}
        }

        @Override
        public void run() {
            try {
                // Deschidem portul pe server
                serverSocket = new ServerSocket(port);
                while (running) {
                    // Metodă blocantă: așteaptă până când un client se conectează
                    Socket client = serverSocket.accept();
                    Log.i(TAG, "Client conectat!");

                    // Pentru fiecare client nou, pornim un thread separat de comunicare (Ex. 3c)
                    new CommunicationThread(client).start();
                }
            } catch (Exception e) {
                Log.e(TAG, "Eroare Server: " + e.getMessage());
            }
        }
    }

    // Se ocupă de dialogul cu UN SINGUR client conectat
    private class CommunicationThread extends Thread {
        private final Socket socket;

        CommunicationThread(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            // BufferedReader: citește datele primite de la client
            // PrintWriter: trimite date înapoi către client
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)) {

                // Pas 1: Citim orasul trimis de client
                String city = in.readLine();
                String informationType = in.readLine();

                if (city == null) city = "";
                city = city.trim();

                WeatherInfo weather = serverThread.getData(city);

                if (weather == null) {
                    // Pas 2: (Ex. 3a) Serverul cere datele de la Google Web Service
                    String rawResponse = fetchWeather(city);

                    // Pas 3: (Ex. 3b) Extragem sugestiile din răspunsul primit (JSON sau text)
                    weather = parseWeather(rawResponse);

                    if (weather != null) {
                        serverThread.setData(city, weather);
                    }
                }
                // Pas 4: (Ex. 3c) Formatăm lista (elemente separate prin virgulă) și trimitem la client
                String result = formatForClient(weather, informationType);
                out.print(result);
                out.flush(); // Ne asigurăm că datele au plecat pe rețea

            } catch (Exception e) {
                Log.e(TAG, "Eroare Comunicare: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }

//        @Override
//        public void run() {
//            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
//                 PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)) {
//
//                // Citim prima linie a cererii HTTP (ex: GET /?city=London&type=all HTTP/1.1)
//                String requestLine = in.readLine();
//                if (requestLine == null) return;
//
//                // Logica simplă de parsare pentru test (extrage orașul și tipul dacă sunt trimise ca parametri)
//                // Pentru testul tău rapid, poți hardcoda orașul sau să folosești Clientul Android.
//                // DAR, pentru a evita eroarea de conexiune, TREBUIE să trimiți headerele HTTP:
//
//                String city = "London"; // sau extrage din requestLine
//                String informationType = "all";
//
//                WeatherInfo weather = serverThread.getData(city);
//                if (weather == null) {
//                    String rawResponse = fetchWeather(city);
//                    weather = parseWeather(rawResponse);
//                    if (weather != null) serverThread.setData(city, weather);
//                }
//
//                String result = formatForClient(weather, informationType);
//
//                // --- SOLUȚIA PENTRU EROARE ---
//                out.println("HTTP/1.1 200 OK");
//                out.println("Content-Type: text/plain");
//                out.println("Content-Length: " + result.length());
//                out.println(""); // Linie goală obligatorie
//                out.print(result);
//                out.flush();
//
//            } catch (Exception e) {
//                Log.e(TAG, "Eroare Comunicare: " + e.getMessage());
//            } finally {
//                try { socket.close(); } catch (Exception ignored) {}
//            }
//        }
    }

    // =========================================================================
    // CLIENT: Se conectează la serverul nostru local (Exercițiul 4)
    // =========================================================================

    private class ClientThread extends Thread {
        private final String host;
        private final int port;
        private final String city;

        ClientThread(String host, int port, String city) {
            this.host = host;
            this.port = port;
            this.city = city;
        }

        @Override
        public void run() {
            try (Socket socket = new Socket(host, port);
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                // Clientul trimite orasul către Server
                out.println(city);

                // Clientul citește răspunsul final de la Server
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                String finalResult = sb.toString().trim();

                // Afișăm rezultatul în UI folosind Handler-ul (nu avem voie să scriem în UI din alt thread)
                uiHandler.post(() -> {
                    resultTextView.setText(finalResult);
                    Toast.makeText(PracticalTest02v5.this, "Autocomplete: " + finalResult, Toast.LENGTH_LONG).show();
                });

            } catch (Exception e) {
                Log.e(TAG, "Eroare Client: " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // LOGICĂ DE PARSARE ȘI WEB (Exercițiul 3 a/b/c)
    // =========================================================================

    // Ex 3a: Realizează cererea HTTP GET către Google
    private String fetchWeather(String city) throws Exception {

        // Cheia API
        String apiKey = "e03c3b32cfb5a6f7069f2ef29237d87e";
        // Encodăm orasul pentru URL (ex: spațiul devine %20)
        String query = URLEncoder.encode(city, "UTF-8");
        URL url = new URL("https://api.openweathermap.org/data/2.5/weather?q=" + query + "&units=metric&appid=" + apiKey);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0"); // Ne dăm drept browser

        // Citim tot corpul răspunsului primit de la Google
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) response.append(line);
            return response.toString();
        } finally {
            conn.disconnect();
        }
    }

    // Clasă pentru a stoca toate datele cerute de subiect
    class WeatherInfo {
        String temperature, windSpeed, condition, pressure, humidity;

        WeatherInfo(String t, String w, String c, String p, String h) {
            this.temperature = t; this.windSpeed = w;
            this.condition = c; this.pressure = p; this.humidity = h;
        }
    }

    // Ex 3b: Extragerea informațiilor utile (Sugestii)
    private WeatherInfo parseWeather(String rawJson) {
        try {
            // Transformăm textul brut într-un obiect JSON principal
            JSONObject jsonObject = new JSONObject(rawJson);

            // 1. Starea generală (se află într-un tablou numit "weather")
            JSONArray weatherArray = jsonObject.getJSONArray("weather");
            String condition = weatherArray.getJSONObject(0).getString("main");

            // 2. Temperatura, Presiunea și Umiditatea (se află în obiectul "main")
            JSONObject main = jsonObject.getJSONObject("main");
            String temp = main.getString("temp");
            String pressure = main.getString("pressure");
            String humidity = main.getString("humidity");

            // 3. Viteza vântului (se află în obiectul "wind")
            JSONObject wind = jsonObject.getJSONObject("wind");
            String windSpeed = wind.getString("speed");

            // Returnăm obiectul cu toate datele împachetate
            return new WeatherInfo(temp, windSpeed, condition, pressure, humidity);

        } catch (Exception e) {
            Log.e("PT02", "Eroare la parsare JSON: " + e.getMessage());
            return null;
        }
    }

    // Ex 3c: Transformă lista de sugestii într-un singur String separat prin virgulă
    private String formatForClient(WeatherInfo weather, String informationType) {
        if (weather == null) return "Eroare: Nu s-au putut prelua datele.";
        StringBuilder sb = new StringBuilder();

                sb.append("Viteza vantului: ").append(weather.windSpeed)
                        .append(", Conditii: ").append(weather.condition)
                        .append(", Temperatura: ").append(weather.temperature)
                        .append(", Presiune: ").append(weather.pressure)
                        .append(", Umiditate: ").append(weather.humidity);

        return sb.toString();
    }
}