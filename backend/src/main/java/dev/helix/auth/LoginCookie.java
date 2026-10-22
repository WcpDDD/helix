package dev.helix.auth;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class LoginCookie {

	static final String NAME = "helix.login";

	private static final Duration MAX_AGE = Duration.ofDays(90);

	private LoginCookie() {
	}

	static void write(HttpServletResponse response, String id) {
		response.addHeader(HttpHeaders.SET_COOKIE, cookie(id, MAX_AGE).toString());
	}

	static void clear(HttpServletResponse response) {
		response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
	}

	static String read(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
				return cookie.getValue();
			}
		}
		return null;
	}

	private static ResponseCookie cookie(String value, Duration maxAge) {
		return ResponseCookie.from(NAME, value)
				.httpOnly(true)
				.path("/")
				.sameSite("Lax")
				.maxAge(maxAge)
				.build();
	}

}
