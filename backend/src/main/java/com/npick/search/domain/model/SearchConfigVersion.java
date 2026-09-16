package com.npick.search.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code search_execution.config_version} 을 만든다 — 공용 규약 「값 해시」의 Java 구현 ({@code docs/contracts/README.md} 공용 규약 1).
 *
 * <pre>
 * &lt;schema&gt;:&lt;정규화 JSON 의 sha256 앞 8자&gt;      예: search-fusion/v1:20dfc0a6
 * 정규화 = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
 * </pre>
 *
 * <p><b>schema 이름만으로는 부족하다.</b> {@code candidate-v1} 같은 문자열을 그대로 버전에 넣으면 가중치를 바꾸고 문자열 갱신을 빠뜨렸을 때 두 실행이 같은 설정으로 보인다. 그래서
 * 이름이 아니라 <b>실제 값</b>을 해시한다 (F-05 완료 기준).
 *
 * <p><b>정본은 {@code ai/src/npick_worker/versioning.py} 다.</b> 규칙이 두 곳에 흩어지면 조용히 어긋나므로 그 파일의 규칙을 그대로 옮겼고 새 해시 방식을 만들지
 * 않았다. 다만 <b>검색 설정 payload 를 계산하는 것은 현재 BE 뿐이다</b> — 파이프라인 단계 산출물과 달리 Python 이 같은 값을 만들지 않는다.
 *
 * <p><b>소수 표기까지 정본과 맞춘다.</b> {@code Double.toString} 을 그대로 쓰면 {@code 0.0001} 이 {@code 1.0E-4} 가 되어 Python 과 다른 바이트열이
 * 나오고, 같은 설정이 다른 버전을 갖는다. 「지금은 BE 만 계산하니 괜찮다」는 이유로 미루면 나중에 규약대로 고치는 순간 <b>과거 실행의 버전이 전부 달라진다</b>. 아래
 * {@link #pythonRepr(double)} 가 CPython 의 {@code float_repr} 규칙을 그대로 옮긴 것이고, 고정 교차 언어 벡터로 묶어 둔다.
 *
 * <p>다른 도메인 모듈이 같은 규약을 Java 로 계산하게 되면 이 클래스를 복제하지 말고 {@code common} 으로 옮긴다.
 */
public final class SearchConfigVersion {

    /** 사람이 로그에서 읽는 값이라 충돌 확률보다 가독성을 택한 길이다 (공용 규약). */
    private static final int HASH_LENGTH = 8;

    private static final String HASH_ALGORITHM = "SHA-256";

    /** 역슬래시. 소스에 리터럴 이스케이프를 쓰면 이 파일을 편집할 때마다 몇 겹인지 세어야 한다. */
    private static final char ESCAPE = (char) 0x5C;

    private SearchConfigVersion() {}

    /**
     * {@code <schema>:<해시>} 를 만든다.
     *
     * @param schema {@code <이름>/v<정수>} 형식의 schema 이름
     * @param payload 실제 사용한 설정. 값은 {@code String}, {@code Boolean}, 정수, {@code Double}, {@code Map}, {@code Iterable},
     *     {@code Enum}, {@code null} 만 허용한다
     */
    public static String of(String schema, Map<String, Object> payload) {
        if (schema == null || schema.isBlank()) throw new IllegalArgumentException("schema is required");
        String canonical = canonicalJson(payload);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JVM 이 제공한다.
            throw new IllegalStateException(HASH_ALGORITHM + " 을 사용할 수 없다", e);
        }
        String hex = HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        return schema + ":" + hex.substring(0, HASH_LENGTH);
    }

    /**
     * 해시 입력용 정규화 JSON. 같은 값이면 항상 같은 바이트열이어야 한다.
     *
     * <p>Jackson 을 쓰지 않는 이유는 소수 표기와 키 정렬을 이 자리에서 고정하기 위해서다. 직렬화 옵션이 어딘가에서 바뀌면 과거 실행과 버전이 어긋나는데 그때 원인을 찾기 어렵다.
     */
    public static String canonicalJson(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String string -> writeString(out, string);
            case Boolean bool -> out.append(bool.booleanValue() ? "true" : "false");
            case Double number -> writeDouble(out, number);
            case Float number -> writeDouble(out, number.doubleValue());
            case Integer number -> out.append(number.intValue());
            case Long number -> out.append(number.longValue());
            case Short number -> out.append(number.intValue());
            case Enum<?> constant -> writeString(out, constant.name());
            case Map<?, ?> map -> writeObject(out, map);
            case Iterable<?> list -> writeArray(out, list);
            default ->
                throw new IllegalArgumentException("Unsupported config payload value type: "
                        + value.getClass().getName());
        }
    }

    /** 키 정렬은 Python 의 {@code sort_keys} 와 같다. 설정 키는 ASCII 라 코드 포인트 비교와 UTF-16 비교가 갈리지 않는다. */
    private static void writeObject(StringBuilder out, Map<?, ?> map) {
        var sorted = new TreeMap<String, Object>();
        map.forEach((key, entryValue) -> {
            if (!(key instanceof String name)) {
                throw new IllegalArgumentException("Config payload keys must be strings");
            }
            sorted.put(name, entryValue);
        });
        out.append('{');
        boolean first = true;
        for (var entry : sorted.entrySet()) {
            if (!first) out.append(',');
            first = false;
            writeString(out, entry.getKey());
            out.append(':');
            write(out, entry.getValue());
        }
        out.append('}');
    }

    /** 목록의 순서는 값의 일부다. 정렬하지 않는다 — 순서가 바뀌면 다른 설정이다. */
    private static void writeArray(StringBuilder out, Iterable<?> values) {
        out.append('[');
        boolean first = true;
        for (Object element : values) {
            if (!first) out.append(',');
            first = false;
            write(out, element);
        }
        out.append(']');
    }

    /** {@code NaN} 과 무한대는 JSON 이 아니다. Python 은 그대로 뱉지만 파싱되지 않는 문자열이라 여기서 막는다. */
    private static void writeDouble(StringBuilder out, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Config payload numbers must be finite: " + value);
        }
        out.append(pythonRepr(value));
    }

    /**
     * CPython 의 {@code repr(float)} 과 같은 문자열 (= {@code json.dumps} 가 소수를 쓰는 방식).
     *
     * <p>두 언어 모두 <b>왕복 가능한 최단 자릿수</b>를 쓰므로 자릿수는 같다. 다른 것은 <b>언제 지수 표기로 바꾸는가</b> 하나뿐이다.
     *
     * <pre>
     *          0.0001        1e-5        1e15                     1e16
     * Python   0.0001        1e-05       1000000000000000.0       1e+16
     * Java     1.0E-4        1.0E-5      1.0E15                   1.0E16
     * </pre>
     *
     * <p>CPython 은 소수점 위치 {@code decpt} 로 가른다 — {@code decpt <= -4} 또는 {@code decpt > 16} 이면 지수 표기다
     * ({@code Python/pystrtod.c} 의 {@code format_float_short}). 지수는 부호를 항상 붙이고 최소 두 자리다. 고정 표기에서 소수부가 없으면 {@code .0} 을
     * 붙인다.
     *
     * <p>자릿수는 {@link Double#toString} 에서 가져온다. JDK 19 부터 이 메서드가 최단 왕복 표현을 보장하므로 자릿수를 직접 구할 필요가 없다.
     */
    static String pythonRepr(double value) {
        if (value == 0) return (Double.doubleToRawLongBits(value) < 0 ? "-0.0" : "0.0");
        String sign = value < 0 ? "-" : "";
        String plain = Double.toString(Math.abs(value));

        String digits;
        int decpt;
        int exponentMark = plain.indexOf('E');
        if (exponentMark >= 0) {
            String mantissa = plain.substring(0, exponentMark).replace(".", "");
            digits = stripTrailingZeros(mantissa);
            // 자바의 지수 표기는 언제나 d.ddd 라 소수점 앞자리가 하나다.
            decpt = Integer.parseInt(plain.substring(exponentMark + 1)) + 1;
        } else {
            int point = plain.indexOf('.');
            String whole = plain.substring(0, point);
            String fraction = plain.substring(point + 1);
            if ("0".equals(whole)) {
                int firstSignificant = 0;
                while (firstSignificant < fraction.length() && fraction.charAt(firstSignificant) == '0') {
                    firstSignificant++;
                }
                digits = stripTrailingZeros(fraction.substring(firstSignificant));
                decpt = -firstSignificant;
            } else {
                digits = stripTrailingZeros(whole + fraction);
                decpt = whole.length();
            }
        }

        if (decpt <= -4 || decpt > 16) {
            String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            int exponent = decpt - 1;
            return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + zeroPaddedExponent(Math.abs(exponent));
        }
        if (decpt <= 0) return sign + "0." + "0".repeat(-decpt) + digits;
        if (decpt >= digits.length()) return sign + digits + "0".repeat(decpt - digits.length()) + ".0";
        return sign + digits.substring(0, decpt) + "." + digits.substring(decpt);
    }

    /** 최단 왕복 자릿수만 남긴다. 값이 0 인 경우는 호출 전에 걸러지므로 빈 문자열이 되지 않는다. */
    private static String stripTrailingZeros(String digits) {
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') end--;
        return digits.substring(0, end);
    }

    /** Python 의 지수는 최소 두 자리다 — {@code 1e-05} 이지 {@code 1e-5} 가 아니다. */
    private static String zeroPaddedExponent(int exponent) {
        String text = Integer.toString(exponent);
        return text.length() >= 2 ? text : "0" + text;
    }

    /** {@code ensure_ascii=False} 라 한글은 그대로 둔다. 제어 문자만 Python 과 같은 짧은 형식으로 이스케이프한다. */
    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> out.append(ESCAPE).append('"');
                case ESCAPE -> out.append(ESCAPE).append(ESCAPE);
                case 0x08 -> out.append(ESCAPE).append('b');
                case 0x0C -> out.append(ESCAPE).append('f');
                case 0x0A -> out.append(ESCAPE).append('n');
                case 0x0D -> out.append(ESCAPE).append('r');
                case 0x09 -> out.append(ESCAPE).append('t');
                default -> {
                    if (character < 0x20) out.append(ESCAPE).append(String.format("u%04x", (int) character));
                    else out.append(character);
                }
            }
        }
        out.append('"');
    }
}
