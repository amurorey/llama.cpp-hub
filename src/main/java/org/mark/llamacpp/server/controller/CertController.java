package org.mark.llamacpp.server.controller;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;

public class CertController implements BaseController {

    private static final Logger logger = LoggerFactory.getLogger(CertController.class);

    @Override
    public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request)
            throws RequestMethodException {
        if (uri.startsWith("/api/cert/generate")) {
            this.handleGenerate(ctx, request);
            return true;
        }
        return false;
    }

    private void handleGenerate(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
            JsonObject body = JsonUtil.parseFullHttpRequestToJsonObject(request, ctx);
            if (body == null)
                return;

            List<String> ips = JsonUtil.getJsonStringList(body.get("ips"));
            List<String> hostnames = JsonUtil.getJsonStringList(body.get("hostnames"));
            int validity = JsonUtil.getJsonInt(body, "validity", 3650);
            if (validity < 1)
                validity = 3650;
            String password = JsonUtil.getJsonString(body, "password");
            if (password.isEmpty()) {
                password = generatePassword();
            }
            int keysize = JsonUtil.getJsonInt(body, "keysize", 2048);
            if (keysize != 2048 && keysize != 4096)
                keysize = 2048;
            String outputDir = JsonUtil.getJsonString(body, "outputDir", "ssl");

            String userCn = JsonUtil.getJsonString(body, "cn");
            if (userCn.isEmpty()) {
                if (hostnames != null && !hostnames.isEmpty()) {
                    userCn = hostnames.get(0);
                } else {
                    userCn = "localhost";
                }
            }

            Path outputPath = Paths.get(outputDir);
            Files.createDirectories(outputPath);
            String keystoreFile = outputPath.resolve("keystore.p12").toString();

            StringBuilder sanBuilder = new StringBuilder();
            if (hostnames != null) {
                for (String h : hostnames) {
                    String t = h.trim();
                    if (!t.isEmpty()) {
                        if (sanBuilder.length() > 0)
                            sanBuilder.append(",");
                        sanBuilder.append("DNS:").append(t);
                    }
                }
            }
            if (!sanBuilder.toString().contains("DNS:localhost")) {
                if (sanBuilder.length() > 0)
                    sanBuilder.append(",");
                sanBuilder.append("DNS:localhost");
            }
            if (!sanBuilder.toString().contains("IP:127.0.0.1")) {
                sanBuilder.append(",IP:127.0.0.1");
            }
            if (ips != null) {
                for (String ip : ips) {
                    String t = ip.trim();
                    if (!t.isEmpty() && !"127.0.0.1".equals(t)) {
                        sanBuilder.append(",IP:").append(t);
                    }
                }
            }

            String cn = userCn;
            String dname = "CN=" + cn + ",OU=llamacpp-hub,O=llamacpp-hub,L=Unknown,ST=Unknown,C=CN";

            String javaHome = System.getProperty("java.home");
            boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
            String keytoolPath = javaHome + File.separator + "bin" + File.separator
                    + (isWindows ? "keytool.exe" : "keytool");

            List<String> cmd = new ArrayList<>();
            cmd.add(keytoolPath);
            cmd.add("-genkeypair");
            cmd.add("-alias");
            cmd.add("server");
            cmd.add("-keyalg");
            cmd.add("RSA");
            cmd.add("-keysize");
            cmd.add(String.valueOf(keysize));
            cmd.add("-keystore");
            cmd.add(keystoreFile);
            cmd.add("-storetype");
            cmd.add("PKCS12");
            cmd.add("-storepass");
            cmd.add(password);
            cmd.add("-keypass");
            cmd.add(password);
            cmd.add("-dname");
            cmd.add(dname);
            cmd.add("-validity");
            cmd.add(String.valueOf(validity));
            cmd.add("-ext");
            cmd.add("SAN=" + sanBuilder.toString());

            String cmdString = cmd.stream().map(s -> {
                if (s.contains(" ") || s.contains(",") || s.contains("="))
                    return "\"" + s + "\"";
                return s;
            }).collect(Collectors.joining(" "));
            logger.info("执行命令: {}", cmdString);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String err = output.toString().trim();
                logger.error("证书生成失败, exitCode={}, 输出: {}", exitCode, err);
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error("证书生成失败: " + err));
                return;
            }

            String expireDate = LocalDate.now().plusDays(validity).format(DateTimeFormatter.ISO_LOCAL_DATE);

            Map<String, Object> data = new HashMap<>();
            data.put("path", keystoreFile);
            data.put("password", password);
            data.put("san", sanBuilder.toString());
            data.put("expireDate", expireDate);
            data.put("keysize", keysize);
            data.put("command", cmdString);

            logger.info("HTTPS证书生成成功: {}", keystoreFile);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));

        } catch (RequestMethodException e) {
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("证书生成异常", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error("证书生成异常: " + e.getMessage()));
        }
    }

    private static String generatePassword() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
