import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class OldPostSecurityServer {

    public static void main(String[] args) throws IOException {

        // 8081번 포트를 사용하는 Java 웹 서버 생성
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 8081),
                0
        );

        // http://127.0.0.1:8081/ 주소 처리
        server.createContext("/", exchange -> {   //다음 주소로 들어오는 요청을 처리

            // GET 요청만 처리
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "허용되지 않은 요청 방식입니다.");
                return;
            }

            // 브라우저에 표시할 로그인 화면
            String html = """
                    <!DOCTYPE html>
                    <html lang="ko">
                    <head>
                        <meta charset="UTF-8">
                        <title>POST 보안 실습</title>
                    </head>
                    <body>
                        <h2>로그인 실습</h2>

                        <form action="/login" method="post">
                            <p>
                                아이디:
                                <input type="text" name="id">
                            </p>

                            <p>
                                비밀번호:
                                <input type="password" name="password">
                            </p>

                            <button type="submit">로그인</button>
                        </form>
                    </body>
                    </html>
                    """;


            sendResponse(exchange, 200, html);  });

        // http://127.0.0.1:8081/login 주소 처리
        server.createContext("/login", exchange -> {

            // 요청 방식 확인
            String method = exchange.getRequestMethod();

            System.out.println();
            System.out.println("[로그인 요청 수신]");
            System.out.println("요청 방식: " + method);

            // POST 요청이 아니면 거부
            if (!"POST".equalsIgnoreCase(method)) {
                sendResponse(exchange, 405, "POST 요청만 허용됩니다.");
                return;
            }

            // 브라우저에서 보낸 POST 데이터 읽기
            //브라우저가 POST 요청 본문에 담아 보낸 데이터를 바이트 배열로 읽음

            byte[] requestBytes =
                    exchange.getRequestBody().readAllBytes();

            //읽은 바이트를 문자열로 변환
            String requestData = new String(
                    requestBytes,
                    StandardCharsets.UTF_8
            );

            // 실습을 위해 받은 데이터 출력
            System.out.println("전송받은 데이터: " + requestData);

            // 브라우저에 표시할 처리 결과
            String result = """
                    <!DOCTYPE html>
                    <html lang="ko">
                    <head>
                        <meta charset="UTF-8">
                        <title>로그인 처리 결과</title>
                    </head>
                    <body>
                        <h2>POST 요청을 받았습니다.</h2>

                        <p>
                            IntelliJ 실행창과 Burp Suite를 확인하세요.
                        </p>

                        <a href="/">로그인 화면으로 돌아가기</a>
                    </body>
                    </html>
                    """;

            sendResponse(exchange, 200, result);
            //로그인 요청을 처리한 결과를 브라우저에 보내는 메서드 호출
        });

        // 서버 실행
        server.start();

        System.out.println("POST 실습 서버가 실행되었습니다.");
        System.out.println("접속 주소: http://127.0.0.1:8081");
        System.out.println("서버를 종료하려면 IntelliJ의 정지 버튼을 누르세요.");
    }

    // HTML 또는 문장을 브라우저에 전송하는 메서드
    // sendResponse()는 상태 코드와 HTML 내용을 브라우저에 전달
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
