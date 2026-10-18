package com.margem.api.seguranca;

import com.margem.api.config.SegurancaProperties.Limite;
import com.margem.api.config.SegurancaProperties.Limites;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Limita requisições antes de chegarem ao controller. Dois usos na mesma cadeia:
 * por IP nas rotas de login (antes da autenticação) e por usuário nas demais (depois dela).
 *
 * <p>O IP vem de {@code getRemoteAddr()}. Atrás de proxy, ligue {@code server.forward-headers-strategy=native}:
 * o Tomcat só aceita X-Forwarded-For de proxies internos, então o cliente não consegue forjar o IP.</p>
 */
public class FiltroLimiteRequisicoes extends OncePerRequestFilter {

    public enum Modo { POR_IP, POR_USUARIO }

    private final Modo modo;
    private final LimitadorRequisicoes limitador;
    private final Limites limites;
    private final RespostasSeguranca respostas;

    public FiltroLimiteRequisicoes(Modo modo, LimitadorRequisicoes limitador, Limites limites, RespostasSeguranca respostas) {
        this.modo = modo;
        this.limitador = limitador;
        this.limites = limites;
        this.respostas = respostas;
    }

    /** Duas instâncias desta classe ficam na mesma cadeia; sem isto a segunda seria pulada como "já executada". */
    @Override
    protected String getAlreadyFilteredAttributeName() {
        return FiltroLimiteRequisicoes.class.getName() + "." + modo;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long espera = 0;
        if (modo == Modo.POR_IP) {
            if (req.getRequestURI().startsWith("/api/auth/") && "POST".equals(req.getMethod())) {
                espera = limitador.consumir("auth:" + req.getRemoteAddr(), limites.authIp());
            }
        } else {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a != null && a.isAuthenticated() && req.getRequestURI().startsWith("/api/")) {
                Limite limite = custoAlto(req) ? limites.analiseUsuario() : limites.apiUsuario();
                espera = limitador.consumir((custoAlto(req) ? "analise:" : "api:") + a.getName(), limite);
            }
        }
        if (espera > 0) {
            respostas.limiteExcedido(res, espera);
            return;
        }
        chain.doFilter(req, res);
    }

    /** Envio, reanálise e formatação: gastam OpenAI, CPU e disco. */
    private static boolean custoAlto(HttpServletRequest req) {
        if (!"POST".equals(req.getMethod())) return false;
        String uri = req.getRequestURI();
        return uri.equals("/api/documentos") || uri.endsWith("/reanalise") || uri.endsWith("/formatacao");
    }
}
