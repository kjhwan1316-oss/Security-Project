// Java에서 기본으로 제공하는 간단한 HTTP 서버 기능
import com.sun.net.httpserver.HttpExchange;

// HTTP 서버를 만들기 위한 클래스
import com.sun.net.httpserver.HttpServer;

// 입출력 오류를 처리하기 위해 사용
import java.io.IOException;

// 서버 응답 내용을 브라우저에 보내기 위해 사용
import java.io.OutputStream;

// 서버가 사용할 IP 주소와 포트를 지정
import java.net.InetSocketAddress;

// URL 인코딩된 값을 한글과 일반 문자열로 복원
import java.net.URLDecoder;

// 문자열을 UTF-8 방식으로 처리
import java.nio.charset.StandardCharsets;

// 세션 ID를 무작위로 생성하기 위해 사용
import java.util.UUID;

// 이름과 값을 한 쌍으로 저장
import java.util.Map;

// 여러 요청이 동시에 들어와도 안전하게 데이터를 저장
import java.util.concurrent.ConcurrentHashMap;


public class LoginRateLimitServer {

    // 로그인을 허용할 최대 실패 횟수
    private static final int MAX_FAILURES = 5;

    // 로그인 차단 시간
    private static final int BLOCK_SECONDS = 30;

    /* 사용자별 로그인 실패 정보를 저장합니다.
     Key:  아이디와 IP 주소를 합친 문자열
     Value:  실패 횟수와 차단 종료 시간이 들어 있는 AttemptInfo 객체   */

    //사용자별 로그인 실패 횟수와 차단 시간을 저장하는 공간을 만듬
    //아이디와 IP 주소를 이름표로 사용하여 로그인 실패 정보를 저장할 수 있는 안전한 Map을 만들고, 그 이름을 loginAttempts라고 정함
    private static final Map<String, AttemptInfo> loginAttempts =
            new ConcurrentHashMap<>();


    public static void main(String[] args) throws IOException {

        // 8083번 포트를 사용하는 HTTP 서버를 만듬
        HttpServer server = HttpServer.create(
                new InetSocketAddress(8083),
                0
        );

        // 사용자가 첫 화면에 접속했을 때 실행할 기능
        server.createContext("/", exchange -> {

            // 요청 주소가 정확히 "/"가 아니면 404를 보냅니다.
            if (!"/".equals(exchange.getRequestURI().getPath())) {

                sendResponse(
                        exchange,
                        404,   //브라우저가 요청한 주소나 자료를 서버에서 찾을 수 없다는 뜻
                        "<h2>페이지를 찾을 수 없습니다.</h2>"
                );

                return;
            }

            // 로그인 입력 화면
            String html = """
                    <!DOCTYPE html>
                    <html lang="ko">
                    <head>
                        <meta charset="UTF-8">
                        <title>반복 로그인 방어 실습</title>
                    </head>

                    <body>
                        <h2>반복 로그인 방어 실습</h2>

                        <p>
                            비밀번호를 5회 틀리면
                            30초 동안 로그인이 차단됩니다.
                        </p>

                        <form action="/login" method="post">

                            <p>
                                아이디:
                                <input  type="text"   name="id"  required >
                            </p>

                            <p>
                                비밀번호:
                                <input  type="password"  name="password"  required >
                            </p>

                            <button type="submit">
                                로그인
                            </button>

                        </form>
                    </body>
                    </html>
                    """;

            // 로그인 화면을 브라우저로 보냄
            sendResponse(exchange, 200, html);
        });


        // /login 주소로 요청이 들어왔을 때 실행
        server.createContext("/login", exchange -> {

            // 요청 방식이 POST가 아니면 거절
            if (!"POST".equalsIgnoreCase(
                    exchange.getRequestMethod()
            )) {

                sendResponse(
                        exchange,
                        405,    //HTTP 상태 코드 : 허용하지 않는 요청 방식
                        "<h2>POST 요청만 허용합니다.</h2>"
                );

                return;
            }

            // 브라우저가 보낸 POST 요청 본문을 읽음
            byte[] requestBytes =
                    exchange.getRequestBody().readAllBytes();

            // 바이트 데이터를 UTF-8 문자열로 변경
            String requestData = new String(
                    requestBytes,
                    StandardCharsets.UTF_8
            );

            // id와 password를 Map 형태로 분리
            Map<String, String> formData =  parseFormData(requestData);

            // id라는 이름으로 전달된 값을 가져옴
            String id = formData.getOrDefault("id", "");

            // password라는 이름으로 전달된 값을 가져옴
            String password =
                    formData.getOrDefault("password", "");

            // 요청을 보낸 컴퓨터의 IP 주소를 가져옴
            String clientIp =
                    exchange.getRemoteAddress()
                            .getAddress()
                            .getHostAddress();

            /*
              아이디와 IP 주소를 합쳐서 구분값을 만듬.
              예: student01|127.0.0.1
             */
            String attemptKey = id + "|" + clientIp;

            // 현재 시각을 밀리초 단위로 구함
            long currentTime = System.currentTimeMillis();

            /* 해당 사용자와 IP의 로그인 실패 정보가 있으면 가져옴
              정보가 없으면 새로운 AttemptInfo 객체를 만듬             */
            AttemptInfo attemptInfo =
                    loginAttempts.computeIfAbsent(
                            attemptKey,
                            key -> new AttemptInfo()
                    );

            /*
             * 동일한 로그인 정보가 동시에 변경되지 않도록
             * attemptInfo 객체를 잠금.
             */
            synchronized (attemptInfo) {

                // 아직 로그인 차단 시간이 끝나지 않았는지 확인
                if (attemptInfo.blockedUntil > currentTime) {

                    // 로그인 차단이 풀릴 때까지 몇 초가 남았는지 계산
                    long remainingSeconds =
                            ( attemptInfo.blockedUntil  - currentTime) / 1000 + 1;

                    // HTTP 429 상태번호와 안내 문장을 보냄
                    sendResponse(
                            exchange,
                            429,  //허용하지 않는 요청 방식
                            """
                            <h2>로그인이 잠시 차단되었습니다.</h2>
                            <p>
                                남은 시간: %d초
                            </p>
                            <a href="/">로그인 화면으로</a>
                            """.formatted(remainingSeconds)
                    );

                    return;
                }

                /*  실습용 아이디와 비밀번호를 검사함
                  실제 프로그램에서는 데이터베이스에서 사용자를 찾고
                  PBKDF2 해시를 이용해 비밀번호를 확인해야 함.  */

                boolean loginSuccess =
                        "student01".equals(id)  && "Test1234".equals(password);

                // 로그인에 성공한 경우
                if (loginSuccess) {

                    // 기존 로그인 실패 기록을 삭제
                    loginAttempts.remove(attemptKey);

                    // 무작위 세션 ID를 생성
                    String sessionId = UUID.randomUUID().toString();

                    /* 브라우저에 세션 ID 쿠키를 보냄
                     HttpOnly:  JavaScript가 쿠키를 직접 읽기 어렵게 함
                     SameSite=Lax:  다른 사이트에서 보내는 일부 요청에
                      쿠키가 자동으로 포함되는 것을 제한.
                      Max-Age=600: 쿠키를 600초 동안 유지
                     */
                    exchange.getResponseHeaders().add(
                            "Set-Cookie",
                            "SESSIONID=" + sessionId
                                    + "; HttpOnly"
                                    + "; SameSite=Lax"
                                    + "; Max-Age=600"
                                    + "; Path=/"
                    );

                    // 로그인 성공 결과를 보냄
                    sendResponse(
                            exchange,
                            200,
                            """
                            <h2>로그인 성공</h2>
                            <p>student01님, 환영합니다.</p>
                            <p>실패 횟수가 초기화되었습니다.</p>
                            <a href="/">로그인 화면으로</a>
                            """
                    );

                    return;
                }

                // 로그인에 실패했으므로 실패 횟수를 1 증가
                attemptInfo.failureCount++;

                // 실패 횟수가 5회 이상인지 확인
                if (attemptInfo.failureCount
                        >= MAX_FAILURES) {

                    // 현재 시각부터 30초 후까지 차단
                    attemptInfo.blockedUntil =
                            currentTime + BLOCK_SECONDS * 1000L;

                    // 새로운 차단 주기를 위해 실패 횟수를 초기화합니다.
                    attemptInfo.failureCount = 0;

                    // 차단 결과를 브라우저로 보냅니다.
                    sendResponse(
                            exchange,
                            429, //요청을 너무 많이 보냄
                            """
                            <h2>로그인 실패 횟수를 초과했습니다.</h2>
                            <p>30초 동안 로그인이 차단됩니다.</p>
                            <a href="/">로그인 화면으로</a>
                            """
                    );

                    return;
                }

                // 차단 전까지 남은 로그인 시도 횟수를 계산
                int remainingAttempts =
                        MAX_FAILURES - attemptInfo.failureCount;

                /*
                 * 서버 실행창에는 비밀번호를 출력하지 않음
                 * 아이디, IP, 실패 횟수 정도만 기록
                 */
                System.out.println(
                        "로그인 실패"
                                + " | 아이디: " + id
                                + " | IP: " + clientIp
                                + " | 실패 횟수: "
                                + attemptInfo.failureCount
                );

                // 로그인 실패 결과를 보냄
                sendResponse(
                        exchange,
                        401,  //로그인 확인 실패(인증되지 않음)
                        """
                        <h2>로그인 실패</h2>
                        <p>아이디 또는 비밀번호가 올바르지 않습니다.</p>
                        <p>남은 시도 횟수: %d회</p>
                        <a href="/">다시 로그인</a>
                        """.formatted(remainingAttempts)
                );
            }
        });


        // HTTP 서버를 시작
        server.start();

        System.out.println(
                "반복 로그인 방어 서버가 실행되었습니다.");

        System.out.println(
                "접속 주소: http://127.0.0.1:8083" );

        System.out.println(
                "실습 계정: student01 / Test1234" );
    }


    /* POST 요청 본문을 Map으로 변환하는 메서드
      입력: id=student01&password=Test1234
      결과:  id→ student01  password → Test1234
     */
    private static Map<String, String> parseFormData(
            String requestData
    ) {

        // 결과를 저장할 Map을 만듬
        Map<String, String> formData =
                new ConcurrentHashMap<>();

        // 요청 본문이 비어 있으면 빈 Map을 반환
        if (requestData == null
                || requestData.isBlank()) {

            return formData;
        }

        // &를 기준으로 각각의 입력 항목을 분리
        String[] pairs = requestData.split("&");

        // 분리된 항목을 하나씩 처리
        for (String pair : pairs) {

            // =을 기준으로 이름과 값을 분리
            String[] parts = pair.split("=", 2);

            // 이름과 값이 모두 있을 때만 처리
            if (parts.length == 2) {

                // 첫 번째 부분을 URL 디코딩
                String name = URLDecoder.decode(
                        parts[0],
                        StandardCharsets.UTF_8
                );

                // 두 번째 부분을 URL 디코딩
                String value = URLDecoder.decode(
                        parts[1],
                        StandardCharsets.UTF_8
                );

                // 이름과 값을 Map에 저장
                formData.put(name, value);
            }
        }

        // 완성된 Map을 반환
        return formData;
    }


    /*  브라우저에 HTTP 응답을 보내는 메서드.  */
    private static void sendResponse(
            HttpExchange exchange,
            int statusCode,
            String responseText
    ) throws IOException {

        // 응답 문자열을 UTF-8 바이트로 변경
        byte[] responseBytes =
                responseText.getBytes(
                        StandardCharsets.UTF_8
                );

        // 응답 내용이 HTML이며 UTF-8이라는 것을 알림
        exchange.getResponseHeaders().set(
                "Content-Type",
                "text/html; charset=UTF-8"
        );

        // 상태번호와 응답 데이터 크기를 보냄
        exchange.sendResponseHeaders(
                statusCode,
                responseBytes.length
        );

        // 응답 데이터를 브라우저로 전송합니다.
        try (OutputStream output =
                     exchange.getResponseBody()) {

            output.write(responseBytes);
        }
    }


    // 로그인 실패 정보를 저장하는 클래스입니다.
    private static class AttemptInfo {

        // 로그인 실패 횟수입니다.
        private int failureCount = 0;

        // 로그인이 차단된 마지막 시각입니다.
        private long blockedUntil = 0;
    }
}