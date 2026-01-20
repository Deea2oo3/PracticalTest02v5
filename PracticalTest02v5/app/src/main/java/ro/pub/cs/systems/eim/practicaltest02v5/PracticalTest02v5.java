package ro.pub.cs.systems.eim.practicaltest02v5;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.HashMap;

class Timezone {
    String value;
    long timestamp;

    Timezone(String value, long timestamp) {
        this.value = value;
        this.timestamp = timestamp;
    }
}

public class PracticalTest02v5 extends AppCompatActivity {

    private static final String TAG = "PT02v5";
    private EditText portEditText, cityEditText;
    private Button startServerButton, stopServerButton, requestButton;
    private TextView resultTextView;

    private ServerThread serverThread;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_practical_test02v5_main);

        portEditText = findViewById(R.id.portEditText);
        cityEditText = findViewById(R.id.cityEditText);
        startServerButton = findViewById(R.id.startServerButton);
        stopServerButton = findViewById(R.id.stopServerButton);
        requestButton = findViewById(R.id.requestButton);
        resultTextView = findViewById(R.id.resultTextView);

        startServerButton.setOnClickListener(v -> {
            int port = parsePort();
            if (port != -1) {
                serverThread = new ServerThread(port);
                serverThread.start();
                Toast.makeText(this, "Server pornit", Toast.LENGTH_SHORT).show();
            }
        });

        stopServerButton.setOnClickListener(v -> {
            if (serverThread != null) {
                serverThread.stopServer();
                serverThread = null;
            }
        });

        requestButton.setOnClickListener(v -> {
            int port = parsePort();
            String command = cityEditText.getText().toString().trim();
            if (port != -1 && !command.isEmpty()) {
                new ClientThread("127.0.0.1", port, command).start();
            }
        });
    }

    private int parsePort() {
        try { return Integer.parseInt(portEditText.getText().toString().trim()); }
        catch (Exception e) { return -1; }
    }

    private class ServerThread extends Thread {
        private final int port;
        private ServerSocket serverSocket;
        private volatile boolean running = true;
        private final HashMap<String, Timezone> dataCache = new HashMap<>();

        ServerThread(int port) { this.port = port; }

        public synchronized void setData(String key, Timezone info) { dataCache.put(key, info); }

        public synchronized Timezone getData(String key) {
            Timezone item = dataCache.get(key);
            if (item == null) return null;
            long currentTime = System.currentTimeMillis() / 1000;
            if ((currentTime - item.timestamp) > 10) {
                dataCache.remove(key);
                return null;
            }
            return item;
        }

        public void stopServer() {
            running = false;
            try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        }

        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(port));
                while (running) {
                    Socket client = serverSocket.accept();
                    new CommunicationThread(client).start();
                }
            } catch (Exception e) {
                Log.e(TAG, "Server error: " + e.getMessage());
            }
        }
    }

    private class CommunicationThread extends Thread {
        private final Socket socket;

        CommunicationThread(Socket socket) { this.socket = socket; }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)) {

                String request = in.readLine();
                if (request == null) return;

                String[] parts = request.split(",");
                String command = parts[0].trim();

                if (command.equalsIgnoreCase("put") && parts.length == 3) {
                    String key = parts[1].trim();
                    String value = parts[2].trim();

                    String unixTime = fetchUnixTime();

                    long now = System.currentTimeMillis() / 1000;
                    serverThread.setData(key, new Timezone(value, now));

                    out.println(unixTime != null ? unixTime : "Stored " + key);

                } else if (command.equalsIgnoreCase("get") && parts.length == 2) {
                    String key = parts[1].trim();
                    Timezone cached = serverThread.getData(key);

                    if (cached != null) {
                        out.println(cached.value);
                    } else {
                        String unixTime = fetchUnixTime();
                        if (unixTime != null) {
                            serverThread.setData(key, new Timezone(unixTime, System.currentTimeMillis() / 1000));
                            out.println(unixTime);
                        } else {
                            out.println("Error");
                        }
                    }
                } else {
                    out.println("Syntax Error");
                }

            } catch (Exception e) {
                Log.e(TAG, "Comm error: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }

        private String fetchUnixTime() {
            try {
                URL url = new URL("https://worldtimeapi.org/api/timezone/Etc/UTC");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject json = new JSONObject(sb.toString());
                return json.getString("unixtime");
            } catch (Exception e) {
                return null;
            }
        }
    }

    private class ClientThread extends Thread {
        private final String host, command;
        private final int port;

        ClientThread(String host, int port, String command) {
            this.host = host; this.port = port; this.command = command;
        }

        @Override
        public void run() {
            try (Socket socket = new Socket(host, port);
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                out.println(command);
                String response = in.readLine();

                uiHandler.post(() -> {
                    resultTextView.setText(response);
                });

            } catch (Exception e) {
                Log.e(TAG, "Client error: " + e.getMessage());
            }
        }
    }
}