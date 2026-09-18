import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/* XSS는 사용자가 입력한 내용을 서버가 그대로 웹페이지에 출력했을 때, 브라우저가 그 내용을 일반 글이 아니라 HTML이나 자바스크립트 코드로 실행하는 취약점 */

public class XssSecurityServer {

    public static void main(String[] args) throws IOException {

        // 8082번 포트에 XSS 실습용 서버 생성
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 8082),
                0
        );

        // 리뷰 입력 화면
        server.createContext("/", exchange -> {

            if (!"GET".equalsIgnoreCase(
                    exchange.getRequestMethod()
            )) {

                sendResponse(
                        exchange,
                        405,
                        "<h2>GET 요청만 허용됩니다.</h2>"
                );

                return;
            }

            String html = """
                    <!DOCTYPE html>
                    <html lang="ko">
                    <head>
                        <meta charset="UTF-8">
                        <title>XSS 보안 실습</title>
                    </head>
                    <body>
                        <h2>영화 리뷰 작성</h2>

                        <form action="/review" method="post">
                            <p>
                                작성자:
                                <input type="text" name="writer">
                            </p>

                            <p>
                                리뷰:
                                <input type="text" name="review">
                            </p>

                            <button type="submit">
                                리뷰 등록
                            </button>
                        </form>
                    </body>
                    </html>
                    """;

            sendResponse(exchange, 200, html);
        });

        // 리뷰 등록 결과 화면
        server.createContext("/review", exchange -> {

            if (!"POST".equalsIgnoreCase(
                    exchange.getRequestMethod()
            )) {

                sendResponse(
                        exchange,
                        405,
                        "<h2>POST 요청만 허용됩니다.</h2>"
                );

                return;
            }

            // POST 요청 본문 읽기
            byte[] requestBytes =
                    exchange.getRequestBody().readAllBytes();

            String requestData = new String(
                    requestBytes,
                    StandardCharsets.UTF_8
            );

            // writer와 review 값 가져오기
            String writer =
                    getFormValue(requestData, "writer");

            String review =
                    getFormValue(requestData, "review");

            // HTML 화면에 출력하기 전에 안전하게 변환
            String safeWriter = escapeHtml(writer);
            String safeReview = escapeHtml(review);


            System.out.println();
            System.out.println("[리뷰 등록 요청]");
            System.out.println("작성자: " + writer);
            System.out.println("리뷰: " + review);

            /*
             * 취약한 부분
             * 사용자가 입력한 writer와 review를
             * 아무 처리 없이 HTML에 직접 넣고 있음
             */
            String result = """
                    <!DOCTYPE html>
                    <html lang="ko">
                    <head>
                        <meta charset="UTF-8">
                        
                        <title>리뷰 등록 결과</title>
                    </head>
                    <body>
                        <h2>등록된 영화 리뷰</h2>

                        <p>작성자: %s</p>

                        <p>리뷰 내용: %s</p>

                        <a href="/">다시 작성하기</a>
                    </body>
                    </html>
                    """.formatted(safeWriter, safeReview);

            sendResponse(exchange, 200, result);
        });

        server.start();

        System.out.println("XSS 실습 서버가 실행되었습니다.");
        System.out.println("접속 주소: http://127.0.0.1:8082");
    }

    // POST 데이터에서 원하는 항목의 값을 가져오는 메서드
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
    // HTML 특수문자를 안전한 문자로 변환
    private static String escapeHtml(String text) {

        if (text == null) { //전달받은 문자열이 없으면 빈 문자열을 반환
            return "";
        }

        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;") //큰따옴표와 작은따옴표 변환
                .replace("'", "&#39;");
    }
    // 브라우저에 응답을 보내는 메서드
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