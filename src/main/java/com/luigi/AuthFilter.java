package com.luigi;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@WebFilter("/*")
public class AuthFilter implements Filter {
	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {

		HttpServletRequest req = (HttpServletRequest) request;
		HttpServletResponse res = (HttpServletResponse) response;

		String uri = req.getRequestURI();
		HttpSession session = req.getSession(false);
		boolean isLoggedIn = (session != null && session.getAttribute("user") != null);

		// Rotte pubbliche
		boolean isPublic = uri.equals("/login.html") || uri.equals("/login") || uri.startsWith("/auth/")
				|| uri.startsWith("/css/") || uri.startsWith("/js/");

		// aggiungi questa condizione:
		if (uri.equals("/admin.html") && (!isLoggedIn || !"admin".equals(session.getAttribute("role")))) {
			res.sendRedirect("/login.html");
			return;
		}

		if (isLoggedIn || isPublic) {
			chain.doFilter(request, response);
		} else {
			res.sendRedirect("/login.html");
		}
	}
}
