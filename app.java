import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class App {

    // Porta do servidor
    static final int PORT = 8080;

    // Nome do arquivo CSV que armazena as tarefas
    static final String CSV = "data_tasks.csv";

    // Capacidade máxima de tarefas
    static final int MAX = 5000;

    // Estruturas de dados para armazenar as tarefas em memória
    static String[] ids = new String[MAX];
    static String[] titulos = new String[MAX];
    static String[] descrs = new String[MAX];
    static int[] status = new int[MAX];     // 0 = TODO, 1 = DOING, 2 = DONE
    static long[] criados = new long[MAX];  // timestamp de criação
    static int n = 0; // número atual de tarefas carregadas

    public static void main(String[] args) throws Exception {
        carregar(); // carrega tarefas já salvas no CSV

        // Criação do servidor HTTP embutido
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", new RootHandler());          // rota principal: página HTML
        server.createContext("/api/tasks", new ApiTasksHandler()); // rota da API REST
        server.setExecutor(null); // executor padrão
        System.out.println("Servindo em http://localhost:" + PORT);
        server.start(); // inicia o servidor
    }

    // Handler da página inicial (HTML Kanban)
    static class RootHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { 
                send(ex, 405, ""); // só aceita GET
                return; 
            }
            byte[] body = INDEX_HTML.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        }
    }

    // Handler da API REST para gerenciar tarefas
    static class ApiTasksHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod(); // método HTTP (GET, POST, etc.)
            URI uri = ex.getRequestURI();
            String path = uri.getPath(); // rota chamada

            try {
                // Lista todas as tarefas
                if ("GET".equals(method) && "/api/tasks".equals(path)) {
                    sendJson(ex, 200, listarJSON());
                    return;
                }

                // Cria uma nova tarefa
                if ("POST".equals(method) && "/api/tasks".equals(path)) {
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String titulo = jsonGet(body, "titulo");
                    String descricao = jsonGet(body, "descricao");
                    if (titulo == null || titulo.isBlank()) {
                        sendJson(ex, 400, "{\"error\":\"titulo obrigatório\"}");
                        return;
                    }
                    Map<String, Object> t = criar(titulo, descricao == null ? "" : descricao);
                    salvar(); // salva no CSV
                    sendJson(ex, 200, toJsonTask(t));
                    return;
                }

                // Atualiza o status de uma tarefa (PATCH)
                if ("PATCH".equals(method) && path.startsWith("/api/tasks/") && path.endsWith("/status")) {
                    String id = path.substring("/api/tasks/".length(), path.length() - "/status".length());
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String stStr = jsonGet(body, "status");
                    if (stStr == null) { sendJson(ex, 400, "{\"error\":\"status ausente\"}"); return; }
                    int st = clampStatus(parseIntSafe(stStr, 0));
                    int i = findIdxById(id);
                    if (i < 0) { sendJson(ex, 404, "{\"error\":\"not found\"}"); return; }
                    status[i] = st;
                    salvar();
                    sendJson(ex, 200, toJsonTask(mapOf(i)));
                    return;
                }

                // Remove uma tarefa (DELETE)
                if ("DELETE".equals(method) && path.startsWith("/api/tasks/")) {
                    String id = path.substring("/api/tasks/".length());
                    int i = findIdxById(id);
                    if (i < 0) { sendJson(ex, 404, "{\"error\":\"not found\"}"); return; }
                    // "shift" nos arrays para remover
                    for (int k = i; k < n - 1; k++) {
                        ids[k] = ids[k+1]; titulos[k] = titulos[k+1]; descrs[k] = descrs[k+1];
                        status[k] = status[k+1]; criados[k] = criados[k+1];
                    }
                    n--;
                    salvar();
                    sendJson(ex, 204, ""); // sucesso sem corpo
                    return;
                }

                // Se nenhuma rota acima for atendida -> 404
                send(ex, 404, "");
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(ex, 500, "{\"error\":\"server\"}");
            }
        }
    }

    // HTML servido na raiz "/" (front-end Kanban com JS)
    static final String INDEX_HTML = """ 
    ... (HTML + CSS + JS do front-end) ...
    """;

    // -------------------------
    // Funções auxiliares
    // -------------------------

    // Carrega tarefas do CSV para memória
    static void carregar() {
        n = 0;
        Path p = Paths.get(CSV);
        if (!Files.exists(p)) return;
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank() || line.startsWith("id;")) continue;
                String[] a = splitCsv(line);
                if (a.length < 5) continue;
                if (n >= MAX) break;
                ids[n] = a[0];
                titulos[n] = a[1];
                descrs[n] = a[2];
                status[n] = clampStatus(parseIntSafe(a[3], 0));
                criados[n] = parseLongSafe(a[4], System.currentTimeMillis());
                n++;
            }
        } catch (IOException e) {
            System.out.println("Falha ao ler CSV: " + e.getMessage());
        }
    }

    // Salva tarefas no CSV
    static void salvar() {
        Path p = Paths.get(CSV);
        try {
            if (p.getParent()!=null) Files.createDirectories(p.getParent());
            try (BufferedWriter bw = Files.newBufferedWriter(p, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                bw.write("id;titulo;descricao;status;criadoEm\n");
                for (int i = 0; i < n; i++) {
                    bw.write(esc(ids[i]) + ";" + esc(titulos[i]) + ";" + esc(descrs[i]) + ";"
                            + status[i] + ";" + criados[i] + "\n");
                }
            }
        } catch (IOException e) {
            System.out.println("Falha ao salvar CSV: " + e.getMessage());
        }
    }

    // Cria uma nova tarefa
    static Map<String, Object> criar(String titulo, String descr) {
        if (n >= MAX) throw new RuntimeException("Capacidade cheia");
        String id = UUID.randomUUID().toString().substring(0,8); // ID curto
        ids[n]=id; titulos[n]=titulo; descrs[n]=descr; status[n]=0; criados[n]=System.currentTimeMillis();
        n++;
        return mapOf(n-1);
    }

    // Busca índice de tarefa pelo ID
    static int findIdxById(String id){
        for (int i=0;i<n;i++) if (ids[i].equals(id)) return i;
        return -1;
    }

    // Cria um Map com os dados de uma tarefa
    static Map<String,Object> mapOf(int i){
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id", ids[i]); m.put("titulo", titulos[i]); m.put("descricao", descrs[i]);
        m.put("status", status[i]); m.put("criadoEm", criados[i]);
        return m;
    }

    // Lista todas as tarefas em formato JSON
    static String listarJSON(){
        StringBuilder sb = new StringBuilder("[");
        for (int i=0;i<n;i++){
            if (i>0) sb.append(',');
            sb.append(toJsonTask(mapOf(i)));
        }
        sb.append(']');
        return sb.toString();
    }

    // Converte tarefa em JSON
    static String toJsonTask(Map<String,Object> t){
        return "{\"id\":\""+jsonEsc((String)t.get("id"))+"\"," +
                "\"titulo\":\""+jsonEsc((String)t.get("titulo"))+"\"," +
                "\"descricao\":\""+jsonEsc((String)t.get("descricao"))+"\"," +
                "\"status\":" + t.get("status") + "," +
                "\"criadoEm\":" + t.get("criadoEm") + "}";
    }

    // Extrai campo de JSON simples (parser rudimentar)
    static String jsonGet(String body, String key){
        if (body == null) return null;
        String s = body.trim();
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}")) s = s.substring(0, s.length()-1);

        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i=0;i<s.length();i++){
            char c = s.charAt(i);
            if (c=='"' && (i==0 || s.charAt(i-1)!='\\')) inQ = !inQ;
            if (c==',' && !inQ){ parts.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        if (cur.length()>0) parts.add(cur.toString());

        for (String kv : parts){
            int i = kv.indexOf(':');
            if (i<=0) continue;
            String k = kv.substring(0,i).trim();
            String v = kv.substring(i+1).trim();
            k = stripQuotes(k);
            if (key.equals(k)){
                v = stripQuotes(v);
                return v;
            }
        }
        return null;
    }

    // Remove aspas de uma string JSON
    static String stripQuotes(String s){
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length()-1).replace("\\\"", "\"");
        }
        return s;
    }

    // Envia resposta HTTP
    static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    // Envia resposta JSON
    static void sendJson(HttpExchange ex, int code, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        send(ex, code, body==null?"":body);
    }

    // Escapar valores para CSV
    static String esc(String s){
        if (s==null) return "";
        if (s.contains(";") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    // Split CSV respeitando aspas
    static String[] splitCsv(String line){
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i=0;i<line.length();i++){
            char c = line.charAt(i);
            if (inQ){
                if (c=='"'){
                    if (i+1<line.length() && line.charAt(i+1)=='"'){ cur.append('"'); i++; }
                    else inQ=false;
                } else cur.append(c);
            } else {
                if (c==';'){ out.add(cur.toString()); cur.setLength(0); }
                else if (c=='"'){ inQ=true; }
                else cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    // Escapar string para JSON
    static String jsonEsc(String s){
        if (s==null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","");
    }

    // Garante que status esteja entre 0 e 2
    static int clampStatus(int s){ return Math.max(0, Math.min(2, s)); }

    // Conversões seguras de string -> número
    static int parseIntSafe(String s, int def){ try { return Integer.parseInt(s.trim()); } catch(Exception e){ return def; } }
    static long parseLongSafe(String s, long def){ try { return Long.parseLong(s.trim()); } catch(Exception e){ return def; } }
}
