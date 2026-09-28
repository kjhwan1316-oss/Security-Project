import com.sun.net.httpserver.HttpExchange; // HTTP 요청을 읽고 응답을 보내는 도구
import com.sun.net.httpserver.HttpServer;   // 간단한 HTTP 서버를 만드는 도구

import java.io.IOException;  // 파일 읽기·쓰기 등 입출력 중 발생하는 예외
import java.io.InputStream;  // 데이터를 바이트 단위로 읽는 통로
import java.io.OutputStream; // 데이터를 바이트 단위로 내보내는 통로

import java.net.InetSocketAddress; // IP 주소와 포트 번호를 함께 지정하는 도구

import java.nio.ByteBuffer;                       // 바이트 데이터를 담고 처리하는 버퍼
import java.nio.charset.CharacterCodingException; // 문자 인코딩·디코딩 중 발생하는 예외
import java.nio.charset.CodingErrorAction;        // 문자 변환 오류의 처리 방법 지정
import java.nio.charset.StandardCharsets;         // UTF-8 등 표준 문자 인코딩 제공

import java.nio.file.Files;              // 파일 생성·읽기·쓰기·삭제 등의 기능
import java.nio.file.Path;               // 파일이나 폴더의 경로 표현
import java.nio.file.StandardOpenOption; // 파일을 열 때 생성·추가·쓰기 등의 옵션 지정

import java.util.Arrays; // 배열 출력·복사·정렬·비교 등의 기능
import java.util.Locale; // 언어·지역에 따른 처리 기준 지정
import java.util.Set;    // 중복 없는 값의 집합을 나타내는 인터페이스
import java.util.UUID;   // 파일 이름 등에 사용할 고유 식별자 생성

import java.util.regex.Matcher; // 정규식으로 실제 문자열 검사
import java.util.regex.Pattern; // 정규식 검사 패턴 준비


public class FileUploadSecurityServer {

    // 서버 포트 번호
    private static final int PORT = 8084;

    // 업로드 파일의 최대 크기: 1MB
    private static final int MAX_FILE_SIZE =
            1024 * 1024; //1MB

    // 파일 정보와 boundary를 포함한 전체 요청의 최대 크기
    //추가한 64KB는 파일 이름, 파일 형식, 파일을 구분하는 boundary 등 부가 정보를 담을 여유 공간
    // 파일 자체의 허용 크기가 늘어나는 것은 아님
    private static final int MAX_REQUEST_SIZE =
            MAX_FILE_SIZE + 64 * 1024;

    // 업로드 허용 확장자
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of(
                    "jpg",
                    "jpeg",
                    "png",
                    "txt"
            );

    // multipart 헤더에서 파일 이름을 찾는 정규식
    // multipart 헤더는 파일 업로드 요청에서 각 데이터가 무엇인지 알려 주는 설명 정보
    private static final Pattern FILENAME_PATTERN =
            Pattern.compile(
                    "filename=\"([^\"]*)\"", //다음 큰따옴표가 나올 때까지 문자를 모아서, 파일 이름으로 꺼낼 수 있게 묶어라
                    Pattern.CASE_INSENSITIVE //CASE_INSENSITIVE : 영문 대소문자를 구분하지 않고 찾겠음
            );

    // 업로드 파일 저장 폴더
    private static final Path UPLOAD_DIRECTORY =
            Path.of("safe_uploads");


    public static void main(String[] args) throws IOException {

        // 업로드 폴더가 없으면 새로 생성
        Files.createDirectories(
                UPLOAD_DIRECTORY
        );

        // 8084번 포트를 사용하는 HTTP 서버 생성
        HttpServer server =
                HttpServer.create(
                        new InetSocketAddress(PORT),
                        0
                );

        // 첫 화면 요청 처리
        server.createContext("/", exchange -> {

            // 브라우저가 요청한 경로 가져오기
            String requestPath =
                    exchange.getRequestURI().getPath();

            // 첫 화면이 아니면 404 응답
            if (!"/".equals(requestPath)) {

                sendResponse(
                        exchange,
                        404,  //요청 경로 없음
                        """
                        <h2>페이지를 찾을 수 없습니다.</h2>
                        <a href="/">첫 화면으로</a>
                        """
                );

                // 현재 요청 처리 종료
                return;
            }

            // 첫 화면은 GET 요청만 허용
            if (!"GET".equalsIgnoreCase(
                    exchange.getRequestMethod()
            )) {

                sendResponse(
                        exchange,
                        405, // 요청 방식 허용 안함
                        """
                        <h2>GET 요청만 허용합니다.</h2>
                        <a href="/">첫 화면으로</a>
                        """
                );

                // 현재 요청 처리 종료
                return;
            }

            // 파일 업로드 HTML 화면
            String html = """
                    <!DOCTYPE html>
                    <html lang="ko">

                    <head>
                        <meta charset="UTF-8">
                        <title>파일 업로드 보안 실습</title>
                    </head>

                    <body>

                        <h2>파일 업로드 보안 실습</h2>

                        <p>
                            JPG, JPEG, PNG, TXT 파일만
                            업로드할 수 있습니다.
                        </p>

                        <p>
                            최대 파일 크기는 1MB입니다.
                        </p>

                        <form
                            action="/upload"
                            method="post"
                            enctype="multipart/form-data">

                            <p>
                                파일 선택:

                                <input
                                    type="file"
                                    name="uploadFile"
                                    required
                                >
                            </p>

                            <button type="submit">
                                파일 업로드
                            </button>

                        </form>

                    </body>

                    </html>
                    """;

            // HTML을 브라우저로 전송
            sendResponse(
                    exchange,
                    200,
                    html
            );
        });


        // 파일 업로드 요청 처리
        server.createContext(
                "/upload",
                exchange -> {

                    try {

                        // POST 요청 여부 확인
                        if (!"POST".equalsIgnoreCase(
                                exchange.getRequestMethod()
                        )) {

                            sendResponse(
                                    exchange,
                                    405,
                                    """
                                    <h2>POST 요청만 허용합니다.</h2>
                                    <a href="/">첫 화면으로</a>
                                    """
                            );

                            // 현재 요청 처리 종료
                            return;
                        }

                        // 요청의 Content-Type 가져오기
                        String contentType =
                                exchange
                                        .getRequestHeaders()
                                        .getFirst(
                                                "Content-Type"
                                        );

                        // multipart/form-data 형식 여부 확인
                        if (contentType == null
                                || !contentType
                                .toLowerCase(Locale.ROOT)
                                .startsWith(
                                        "multipart/form-data"
                                )) {

                            sendResponse(
                                    exchange,
                                    400,
                                    """
                                    <h2>올바른 파일 업로드 요청이 아닙니다.</h2>
                                    <a href="/">다시 선택하기</a>
                                    """
                            );

                            return;
                        }

                        // Content-Type에서 boundary 추출
                        //문자열에서 데이터를 구분하는 경계 문자열인 boundary를 꺼내 변수에 저장
                        String boundary = extractBoundary(contentType);

                        // boundary 존재 여부 확인
                        if (boundary == null  || boundary.isBlank()) {
                            sendResponse(
                                    exchange,
                                    400,
                                    """
                                    <h2>파일 구분 정보를 찾을 수 없습니다.</h2>
                                    <a href="/">다시 선택하기</a>
                                    """
                            );

                            return;
                        }

                        // Content-Length 헤더 가져오기
                        String contentLengthText =
                                exchange
                                        .getRequestHeaders()
                                        .getFirst(
                                                "Content-Length"
                                        );

                        // Content-Length가 있는 경우 요청 크기 검사
                        if (contentLengthText != null) {

                            try {

                                // 문자열을 long 자료형으로 변환
                                long contentLength =
                                        Long.parseLong(contentLengthText);

                                // 전체 요청 크기 제한 검사
                                if (contentLength > MAX_REQUEST_SIZE) {
                                    sendResponse(
                                            exchange,
                                            413, //허용크기보다 큼
                                            """
                                            <h2>파일 크기가 너무 큽니다.</h2>
                                            <p>최대 파일 크기는 1MB입니다.</p>
                                            <a href="/">다시 선택하기</a>
                                            """
                                    );

                                    return;
                                }

                            } catch (NumberFormatException e) {

                                // 요청 크기 정보가 숫자가 아닌 경우
                                sendResponse(
                                        exchange,
                                        400,
                                        """
                                        <h2>요청 크기 정보가 잘못되었습니다.</h2>
                                        <a href="/">다시 선택하기</a>
                                        """
                                );

                                return;
                            }
                        }

                        // 요청 본문을 저장할 바이트 배열
                        byte[] requestBody;

                        //제한 크기까지만 읽으면 다음 두 경우를 구분하기 어려움
                        //1. 데이터가 정확히 제한 크기만큼 있는 경우
                        //2.  데이터가 제한보다 더 있지만, 제한 크기까지만 읽은 경우
                        //=> 1바이트를 더 읽을 수 있으면 “제한을 넘었다”는 사실을 확인

                        // 제한 크기보다 1바이트 더 읽어 초과 여부 확인
                        try (InputStream input = exchange.getRequestBody()) {
                            requestBody = input.readNBytes(MAX_REQUEST_SIZE + 1);
                        }

                        // 실제 요청 본문의 크기 검사
                        if (requestBody.length > MAX_REQUEST_SIZE) {
                            sendResponse(
                                    exchange,
                                    413,
                                    """
                                    <h2>파일 크기가 너무 큽니다.</h2>
                                    <p>최대 파일 크기는 1MB입니다.</p>
                                    <a href="/">다시 선택하기</a>
                                    """
                            );

                            return;
                        }

                        // multipart 요청에서 파일 정보 추출
                        UploadedFile uploadedFile =
                                parseUploadedFile(requestBody, boundary);
                        //requestBody   브라우저가 보낸 요청 본문 전체
                        //boundary   요청 본문에서 각 부분을 구분하는 표시
                        //parseUploadedFile(...)   구분 표시를 이용해 파일 정보를 찾아 꺼내는 메서드

                        // 업로드 파일 존재 여부 확인
                        if (uploadedFile == null) {

                            sendResponse(
                                    exchange,
                                    400,
                                    """
                                    <h2>업로드된 파일을 찾을 수 없습니다.</h2>
                                    <a href="/">다시 선택하기</a>
                                    """
                            );

                            return;
                        }

                        // 브라우저가 보낸 원래 파일 이름
                        String originalFileName =  uploadedFile.fileName();

                        // 위험한 원래 파일 이름을 먼저 거부
//                        if (originalFileName.contains("..") || originalFileName.contains("/") || originalFileName.contains("\\")) {
//
//                            sendResponse(exchange, 400, """
//                                    <h2>위험한 파일 이름입니다.</h2>
//                                    <p>파일 경로 문자는 사용할 수 없습니다.</p>
//                                    <a href="/">다시 선택하기</a>
//                                    """
//                            );
//
//                            return;
//                        }

                        // 안전성이 확인된 후 파일 이름 정리
                        // 파일 이름에서 폴더 경로 제거
                        String safeOriginalName = removePathFromFileName(originalFileName);

                        // 파일 이름의 안전성 검사
                        if (!isSafeFileName(safeOriginalName)) {
                            sendResponse(
                                    exchange,
                                    400,
                                    """
                                    <h2>파일 이름이 올바르지 않습니다.</h2>
                                    <a href="/">다시 선택하기</a>
                                    """
                            );

                            return;
                        }

                        // 파일 확장자 추출
                        String extension = getExtension(safeOriginalName);

                        // 허용된 확장자 여부 검사
                        if (!ALLOWED_EXTENSIONS.contains(
                                extension
                        )) {

                            sendResponse(
                                    exchange,
                                    415,
                                    """
                                    <h2>허용하지 않는 파일 형식입니다.</h2>

                                    <p>
                                        JPG, JPEG, PNG, TXT 파일만
                                        업로드할 수 있습니다.
                                    </p>

                                    <a href="/">다시 선택하기</a>
                                    """
                            );

                            return;
                        }

                        // 실제 파일의 바이트 데이터
                        byte[] fileData =  uploadedFile.data();

                        // 빈 파일 여부 검사
                        if (fileData.length == 0) {

                            sendResponse(
                                    exchange,
                                    400,
                                    """
                                    <h2>빈 파일은 업로드할 수 없습니다.</h2>
                                    <a href="/">다시 선택하기</a>
                                    """
                            );
                            return;
                        }

                        // 실제 파일 크기 검사
                        if (fileData.length   > MAX_FILE_SIZE) {

                            sendResponse(
                                    exchange,
                                    413,
                                    """
                                    <h2>파일 크기가 1MB를 초과했습니다.</h2>
                                    <a href="/">다시 선택하기</a>
                                    """
                            );

                            return;
                        }

                        // 확장자와 실제 파일 내용 비교
                        if (!isValidFileContent(
                                extension,
                                fileData
                        )) {

                            sendResponse(
                                    exchange,
                                    415,
                                    """
                                    <h2>파일 내용과 확장자가 일치하지 않습니다.</h2>

                                    <p>
                                        파일 이름만 변경한
                                        위장 파일일 수 있습니다.
                                    </p>

                                    <a href="/">다시 선택하기</a>
                                    """
                            );

                            return;
                        }

                        // UUID를 사용한 새로운 저장 파일 이름 생성
                        // UUID.randomUUID()는 다른 파일과 겹치기 어려운 식별값을 만듬
                        String savedFileName =  UUID.randomUUID()  + "." + extension;

                        // 업로드 폴더와 파일 이름 연결
                        Path savePath =
                                UPLOAD_DIRECTORY.resolve(savedFileName); // resolve()는 폴더 경로 뒤에 새 파일 이름을 붙임

                        // 업로드 폴더의 절대 경로 정규화
                        Path normalizedUploadDirectory =
                                UPLOAD_DIRECTORY.toAbsolutePath().normalize();

                        // 저장할 파일의 절대 경로 정규화
                        Path normalizedSavePath =
                                savePath.toAbsolutePath().normalize();

                        // 저장 경로가 업로드 폴더 내부인지 검사
                        if (!normalizedSavePath.startsWith(normalizedUploadDirectory)) {
                            sendResponse(
                                    exchange,
                                    400,
                                    """
                                    <h2>잘못된 파일 저장 경로입니다.</h2>
                                    <a href="/">다시 선택하기</a>
                                    """
                            );
                            return;
                        }

                        // 검사에 통과한 파일 저장
                        Files.write(
                                normalizedSavePath,
                                fileData,
                                StandardOpenOption.CREATE_NEW
                        );

                        // 파일 이름에 HTML 출력 인코딩 적용
                        String escapedOriginalName =
                                escapeHtml(safeOriginalName);

                        // 파일 업로드 성공 결과 전송
                        sendResponse(
                                exchange,
                                200,
                                """
                                <h2>파일 업로드 성공</h2>

                                <p>
                                    원래 파일 이름: %s
                                </p>

                                <p>
                                    서버 저장 이름: %s
                                </p>

                                <p>
                                    파일 크기: %d바이트
                                </p>

                                <p>
                                    파일 확장자: %s
                                </p>

                                <a href="/">다른 파일 업로드</a>
                                """.formatted(
                                        escapedOriginalName,
                                        savedFileName,
                                        fileData.length,
                                        extension
                                )
                        );

                        // IntelliJ 실행창에 업로드 결과 출력
                        System.out.println(
                                "파일 업로드 성공"
                                        + " | 원래 이름: "
                                        + safeOriginalName
                                        + " | 저장 이름: "
                                        + savedFileName
                                        + " | 크기: "
                                        + fileData.length
                                        + "바이트"
                        );

                    } catch (Exception e) {

                        // 개발자 확인용 오류 출력
                        e.printStackTrace();

                        // 브라우저에는 자세한 내부 오류를 숨김
                        sendResponse(
                                exchange,
                                500,  //서버 내부 처리 중 오류가 발생
                                """
                                <h2>파일 처리 중 오류가 발생했습니다.</h2>
                                <a href="/">첫 화면으로</a>
                                """
                        );
                    }
                }
        );

        // HTTP 서버 시작
        server.start();

        // 서버 실행 정보 출력
        System.out.println("파일 업로드 보안 서버가 실행되었습니다.");

        // 접속 주소 출력
        System.out.println("접속 주소: http://127.0.0.1:" + PORT );

        // 파일 저장 폴더 출력
        System.out.println(
                "저장 폴더: " + UPLOAD_DIRECTORY.toAbsolutePath().normalize());
    }

    // Content-Type에서 multipart boundary 추출
    private static String extractBoundary(
            String contentType
    ) {
        // 세미콜론을 기준으로 문자열 분리
        String[] parts =  contentType.split(";");

        // 분리된 항목을 순서대로 검사
        for (String part : parts) {

            // 앞뒤 공백 제거
            String trimmedPart = part.trim();

            // boundary=으로 시작하는지 확인
            if (trimmedPart.startsWith("boundary=" )) {

                // boundary= 다음 문자열 추출
                String boundary =
                        trimmedPart.substring("boundary=".length());

                // boundary를 감싼 큰따옴표 제거
                if (boundary.startsWith("\"")
                        && boundary.endsWith("\"")
                        && boundary.length() >= 2) {

                    boundary =  boundary.substring(1, boundary.length() - 1);
                }

                // boundary 반환
                return boundary;
            }
        }

        // boundary를 찾지 못한 경우
        return null;
    }


    // multipart 요청에서 파일 이름과 파일 데이터 추출
    private static UploadedFile parseUploadedFile(
            byte[] requestBody,
            String boundary
    ) {

        // 위치 검색을 위해 요청 본문을 ISO-8859-1 문자열로 변환
        String bodyText =
                new String(
                        requestBody,
                        StandardCharsets.ISO_8859_1
                );

        // filename 항목의 위치 검색
        int fileNamePosition =
                bodyText.indexOf("filename=\"" );

        // filename을 찾지 못한 경우
        if (fileNamePosition < 0) {
            return null;
        }

        // 파일 헤더의 끝 위치 검색
        //헤더와 실제 파일 데이터 사이에 빈 줄 하나가 있음
        //\r\n\r\n -> 줄바꿈 두 번 → 사이에 빈 줄 생성
        int headerEnd =
                bodyText.indexOf(
                        "\r\n\r\n",
                        fileNamePosition
                );

        // 헤더의 끝을 찾지 못한 경우
        if (headerEnd < 0) {
            return null;
        }

        // 파일 관련 헤더만 추출
        String fileHeaders =
                bodyText.substring(
                        fileNamePosition,
                        headerEnd
                );

        // 정규식을 사용한 파일 이름 검색
        Matcher matcher =
                FILENAME_PATTERN.matcher(
                        fileHeaders
                );

        // 파일 이름을 찾지 못한 경우
        if (!matcher.find()) {
            return null;
        }

        // 큰따옴표 안의 파일 이름 추출
        String fileName =  matcher.group(1);

        // 파일 데이터의 시작 위치 계산
        //"\r\n\r\n" 이 4개를 건너뛰면 실제 파일 데이터가 시작
        int dataStart =  headerEnd + 4;

        // 파일 데이터 뒤에 나오는 boundary 문자열
        String endMarker =  "\r\n--" + boundary;

        // 파일 데이터의 끝 위치 검색
        int dataEnd =
                bodyText.indexOf(
                        endMarker,
                        dataStart
                );

        // 마지막 boundary를 찾지 못한 경우
        if (dataEnd < 0) {
            return null;
        }

        // 실제 파일 데이터만 바이트 배열로 복사
        byte[] fileData =
                Arrays.copyOfRange(
                        requestBody,
                        dataStart,
                        dataEnd
                );

        // 파일 이름과 파일 데이터를 하나로 묶어 반환
        return new UploadedFile(
                fileName,
                fileData
        );
    }


    // 파일 이름에서 폴더 경로 제거
    private static String removePathFromFileName(
            String fileName
    ) {

        // 파일 이름이 null인 경우
        if (fileName == null) {
            return "";
        }

        // Windows 역슬래시를 일반 슬래시로 변경
        String changedName =
                fileName.replace(
                        '\\',
                        '/'
                );

        // 마지막 슬래시 위치 검색
        int lastSlash =  changedName.lastIndexOf('/');

        // 경로가 포함된 경우 파일 이름만 추출
        if (lastSlash >= 0) {
            changedName =  changedName.substring(lastSlash + 1 );
        }

        // 앞뒤 공백 제거 후 반환
        return changedName.trim();
    }


    // 파일 이름의 안전성 검사
    private static boolean isSafeFileName(
            String fileName
    ) {

        // 파일 이름이 없거나 비어 있는 경우
        if (fileName == null  || fileName.isBlank()) {
            return false;
        }

        // 파일 이름 길이를 100자로 제한
        if (fileName.length() > 100) {
            return false;
        }

        // 상위 폴더 이동을 나타내는 .. 차단
        if (fileName.contains("..")) {
            return false;
        }

        // 한글·영문·숫자·일부 안전한 기호만 허용
        return fileName.matches(
                "[가-힣A-Za-z0-9._ -]+"
        );
    }


    // 파일 이름에서 확장자 추출
    private static String getExtension(
            String fileName
    ) {

        // 마지막 마침표 위치 검색
        int dotIndex =  fileName.lastIndexOf('.');

        // 확장자가 없는 경우
        if (dotIndex < 0  || dotIndex  == fileName.length() - 1) {
            return "";
        }

        // 확장자를 소문자로 변경하여 반환
        return fileName
                .substring(dotIndex + 1)
                .toLowerCase(Locale.ROOT);
    }


    // 확장자와 실제 파일 내용 검사
    private static boolean isValidFileContent(
            String extension,
            byte[] fileData
    ) {

        // PNG 파일 검사
        if ("png".equals(extension)) {
            return isPng(fileData);
        }

        // JPG 또는 JPEG 파일 검사
        if ("jpg".equals(extension)
                || "jpeg".equals(extension)) {

            return isJpeg(fileData);
        }

        // TXT 파일 검사
        if ("txt".equals(extension)) {
            return isUtf8Text(fileData);
        }

        // 허용되지 않은 파일
        return false;
    }


    // PNG 파일의 고유 시작 바이트 검사
    private static boolean isPng(
            byte[] fileData
    ) {

        // PNG 검사에 필요한 최소 크기 확인
        if (fileData.length < 8) {
            return false;
        }

        // PNG 시그니처 검사: 89 50 4E 47 0D 0A 1A 0A
        // 시그니처는 파일 형식을 알아보는 일종의 식별 표시. 코드는 파일의 첫 바이트부터 하나씩 비교
        return (fileData[0] & 0xFF) == 0x89
                && (fileData[1] & 0xFF) == 0x50
                && (fileData[2] & 0xFF) == 0x4E
                && (fileData[3] & 0xFF) == 0x47
                && (fileData[4] & 0xFF) == 0x0D
                && (fileData[5] & 0xFF) == 0x0A
                && (fileData[6] & 0xFF) == 0x1A
                && (fileData[7] & 0xFF) == 0x0A;
    }


    // JPEG 파일의 고유 시작 바이트 검사
    private static boolean isJpeg(
            byte[] fileData
    ) {

        // JPEG 검사에 필요한 최소 크기 확인
        if (fileData.length < 3) {
            return false;
        }

        // JPEG 시그니처 검사: FF D8 FF
        return (fileData[0] & 0xFF) == 0xFF
                && (fileData[1] & 0xFF) == 0xD8
                && (fileData[2] & 0xFF) == 0xFF;
    }


    // TXT 파일이 정상적인 UTF-8 문서인지 검사
    private static boolean isUtf8Text(
            byte[] fileData
    ) {

        // NULL 바이트 포함 여부 검사
        for (byte value : fileData) {

            if (value == 0) {
                return false;
            }
        }

        try {

            // 잘못된 UTF-8 데이터 발견 시 오류 발생
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(
                            CodingErrorAction.REPORT
                    )
                    .onUnmappableCharacter(
                            CodingErrorAction.REPORT
                    )
                    .decode(
                            ByteBuffer.wrap(fileData)
                    );

            // 정상적인 UTF-8 문서
            return true;

        } catch (CharacterCodingException e) {

            // 정상적인 UTF-8 문서가 아님
            return false;
        }
    }


    // HTML에서 특별한 의미가 있는 문자 변환
    private static String escapeHtml(
            String text
    ) {

        // HTML 출력 인코딩 적용
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

        // 응답 문자열을 UTF-8 바이트로 변환
        byte[] responseBytes =
                responseText.getBytes(
                        StandardCharsets.UTF_8
                );

        // 응답 형식과 문자 인코딩 설정
        exchange.getResponseHeaders().set(
                "Content-Type",
                "text/html; charset=UTF-8"
        );

        // HTTP 상태번호와 응답 크기 전송
        exchange.sendResponseHeaders(
                statusCode,
                responseBytes.length
        );

        // HTTP 응답 본문 전송
        try (OutputStream output =  exchange.getResponseBody()) {
            output.write(responseBytes);
        }
    }


    // 파일 이름과 데이터를 하나로 묶는 자료형
    private record UploadedFile(
            String fileName,
            byte[] data
    ) {
    }
}
