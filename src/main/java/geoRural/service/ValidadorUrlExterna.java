package geoRural.service;

import geoRural.exception.ColetaFalhouException;
import geoRural.exception.RequisicaoInvalidaException;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Confere um link antes de o servidor buscá-lo. O link é digitado na tela, e o servidor é que
 * faz a requisição: sem esta trava, dá para usar a coleta para ler a rede interna. Na VM da OCI,
 * http://169.254.169.254 entrega as credenciais da própria VM (Instance Principal).
 *
 * Aceita só http e https, para um servidor que resolva só para endereços públicos. Vale também
 * para cada redirecionamento (ColetaLinkServiceImpl confere de novo antes de seguir).
 *
 * Limite conhecido: entre esta conferência e a conexão o nome é resolvido de novo, então um
 * DNS malicioso ainda poderia trocar o endereço no meio (DNS rebinding). Para fechar isso, a
 * conexão teria de ir direto ao IP conferido; fica para quando a coleta passar ao Airflow.
 */
@Component
public class ValidadorUrlExterna {

    public URI validar(String url) {
        URI uri;
        try {
            uri = new URI(url == null ? "" : url.trim());
        } catch (URISyntaxException e) {
            throw invalido("O link não é uma URL válida.");
        }

        String esquema = uri.getScheme();
        if (esquema == null || !(esquema.equalsIgnoreCase("http") || esquema.equalsIgnoreCase("https"))) {
            throw invalido("Só links http ou https podem ser coletados.");
        }
        String servidor = uri.getHost();
        if (servidor == null || servidor.isBlank()) {
            throw invalido("O link não diz qual é o servidor.");
        }

        InetAddress[] enderecos;
        try {
            enderecos = InetAddress.getAllByName(servidor);
        } catch (UnknownHostException e) {
            throw new ColetaFalhouException("Não foi possível encontrar o servidor " + servidor + ".");
        }
        for (InetAddress endereco : enderecos) {
            if (interno(endereco)) {
                throw invalido("O link aponta para um endereço interno (" + servidor + "), que não pode ser coletado.");
            }
        }
        return uri;
    }

    /** Endereços que não são da internet pública: a própria máquina, a rede local, a da nuvem. */
    static boolean interno(InetAddress endereco) {
        if (endereco.isAnyLocalAddress() || endereco.isLoopbackAddress() || endereco.isLinkLocalAddress()
                || endereco.isSiteLocalAddress() || endereco.isMulticastAddress()) {
            return true;  // 0.0.0.0, 127.x, 169.254.x (metadados da nuvem), 10.x, 172.16-31.x, 192.168.x
        }
        byte[] b = endereco.getAddress();
        if (b.length == 4) {
            int p0 = b[0] & 0xff;
            int p1 = b[1] & 0xff;
            return p0 == 0
                    || (p0 == 100 && p1 >= 64 && p1 <= 127)   // 100.64/10, rede interna de operadoras e nuvens
                    || (p0 == 198 && (p1 == 18 || p1 == 19))  // 198.18/15, testes
                    || p0 >= 240;                             // reservados
        }
        return (b[0] & 0xfe) == 0xfc;  // IPv6 fc00::/7, o equivalente da rede local
    }

    private static RequisicaoInvalidaException invalido(String mensagem) {
        return new RequisicaoInvalidaException(mensagem, List.of("urlOrigem"));
    }
}
