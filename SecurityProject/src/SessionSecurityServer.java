import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID; //임의의 세션 아이디를 생성하는 클래스
import java.util.concurrent.ConcurrentHashMap;

public class SessionSecurityServer {

    // 세션 ID와 사용자 아이디를 서버 메모리에 저장
    private static final Map<String, String> sessions =
            new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {

        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 8083),
                0
        );

        // 로그인 화면
        server.createContext("/", exchange -> {

            if (!"GET".equalsIgnoreCase(
                    exchange.getRequestMethod()
            )) {
                sendResponse(exchange, 405,
                        "<h2>GET 요청만 허용됩니다.</h2>");
                return;
            }

            String html = """
                    <!DOCTYPE html>
                    <html lang="ko">
                    <head>
                        <meta charset="UTF-8">
                        <title>세션 실습</title>
                    </head>
                    <body>
                        <h2>쿠키와 세션 로그인 실습</h2>

                        <form action="/login"
                              method="post"
                              accept-charset="UTF-8">

                            <p>
                                아이디:
                                <input type="text" name="id">
                            </p>

                            <p>
                                비밀번호:
                                <input type="password"
                                       name="password">
                            </p>

                            <button type="submit">로그인</button>
                        </form>

                        <p>
                            실습 계정:
                            student01 / test1234
                        </p>
                    </body>
                    </html>
                    """;

            sendResponse(exchange, 200, html);
        });

        // 로그인 처리
        server.createContext("/login", exchange -> {

            if (!"POST".equalsIgnoreCase(
                    exchange.getRequestMethod()
            )) {
                sendResponse(exchange, 405,
                        "<h2>POST 요청만 허용됩니다.</h2>");
                return;
            }

            String requestData = new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            );

            String id = getFormValue(requestData, "id");
            String password =
                    getFormValue(requestData, "password");

            /*
             * 세션 원리를 위한 실습용 인증
             * 실제 프로그램에서는 DB와 비밀번호 해시를 사용해야 함
             */
            if (!"student01".equals(id) ||
                    !"test1234".equals(password)) {

                sendResponse(
                        exchange,
                        401,
                        """
                        <h2>로그인 실패</h2>
                        <p>아이디 또는 비밀번호가 올바르지 않습니다.</p>
                        <a href="/">다시 로그인하기</a>
                        """
                );

                return;
            }

            // 예측하기 어려운 새로운 세션 ID 생성
            //로그인에 성공하면 세션 아이디 생성
            String sessionId =
                    UUID.randomUUID().toString();

            // 서버 메모리에 세션 저장
            sessions.put(sessionId, id);

            // 브라우저에 세션 ID 쿠키 전달
            exchange.getResponseHeaders().add(
                    "Set-Cookie", //서버가 브라우저에게 응답으로 쿠키를 전달
                    "SESSIONID=" + sessionId +
                            "; Path=/; HttpOnly; SameSite=Lax"
                    //HttpOnly:  개밸자 도구 -> 콘솔 -> 안보인
                    //SameSite=Lax: 다른 곳에서 쿠키를 호출해도 제한
            );

            String result = """
                    <h2>로그인 성공</h2>

                    <p>서버에서 세션을 만들었습니다.</p>

                    <p>
                        브라우저에는 세션 ID 쿠키가 저장됩니다.
                    </p>

                    <a href="/mypage">내 정보 보기</a>
                    """;

            sendResponse(exchange, 200, result);
        });

        // 로그인한 사용자만 볼 수 있는 화면
        server.createContext("/mypage", exchange -> {

            String sessionId =
                    getSessionIdFromCookie(exchange);

            String loginId = null;

            if (sessionId != null) {
                loginId = sessions.get(sessionId);
            }

            // 유효한 세션이 없으면 접근 거부
            if (loginId == null) {

                sendResponse(
                        exchange,
                        401,
                        """
                        <h2>접근할 수 없습니다.</h2>
                        <p>먼저 로그인하세요.</p>
                        <a href="/">로그인 화면</a>
                        """
                );

                return;
            }

            String safeId = escapeHtml(loginId);

            String result = """
                    <h2>내 정보</h2>

                    <p>로그인 사용자: %s</p>

                    <p>유효한 세션이 확인되었습니다.</p>

                    <a href="/logout">로그아웃</a>
                    """.formatted(safeId);

            sendResponse(exchange, 200, result);
        });

        // 로그아웃 처리
        server.createContext("/logout", exchange -> {

            String sessionId =
                    getSessionIdFromCookie(exchange);

            if (sessionId != null) {
                sessions.remove(sessionId);
            }

            // 브라우저의 쿠키 삭제
            exchange.getResponseHeaders().add(
                    "Set-Cookie",
                    "SESSIONID=; Path=/; HttpOnly; " +
                            "SameSite=Lax; Max-Age=0"
            );

            sendResponse(
                    exchange,
                    200,
                    """
                    <h2>로그아웃되었습니다.</h2>
                    <a href="/">다시 로그인하기</a>
                    """
            );
        });

        server.start();

        System.out.println("세션 실습 서버가 실행되었습니다.");
        System.out.println("접속 주소: http://127.0.0.1:8083");
    }

    // POST 폼에서 원하는 값 가져오기
    private static String getFormValue(
            String requestData,
            String targetName
    ) {

        String[] items = requestData.split("&");

        for (String item : items) {

            String[] nameAndValue = item.split("=", 2);

            String name = URLDecoder.decode(
                    nameAndValue[0],
                    StandardCharsets.UTF_8
            );

            if (name.equals(targetName)) {

                if (nameAndValue.length == 2) {
                    return URLDecoder.decode(
                            nameAndValue[1],
                            StandardCharsets.UTF_8
                    );
                }

                return "";
            }
        }

        return "";
    }

    // 요청 쿠키에서 SESSIONID 찾기
    private static String getSessionIdFromCookie(
            HttpExchange exchange
    ) {

        String cookieHeader =
                exchange.getRequestHeaders().getFirst("Cookie");

        if (cookieHeader == null) {
            return null;
        }

        String[] cookies = cookieHeader.split(";");

        for (String cookie : cookies) {

            String[] nameAndValue =
                    cookie.trim().split("=", 2);

            if (nameAndValue.length == 2 &&
                    nameAndValue[0].equals("SESSIONID")) {

                return nameAndValue[1];
            }
        }

        return null;
    }

    // HTML 출력값을 안전하게 변환
    private static String escapeHtml(String text) {

        if (text == null) {
            return "";
        }

        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // 브라우저에 HTTP 응답 전송
    private static void sendResponse(
            HttpExchange exchange,
            int statusCode,
            String responseText
    ) throws IOException {

        byte[] responseBytes =
                responseText.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set(
                "Content-Type",
                "text/html; charset=UTF-8"
        );

        exchange.sendResponseHeaders(
                statusCode,
                responseBytes.length
        );

        try (OutputStream output =
                     exchange.getResponseBody()) {

            output.write(responseBytes);
        }
    }
}