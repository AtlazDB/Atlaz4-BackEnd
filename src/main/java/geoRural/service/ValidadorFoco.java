package geoRural.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import geoRural.dto.FocoLido;

/**
 * Separa as linhas do CSV do INPE em três: fora do Paraná (descarte silencioso — o arquivo traz o
 * Brasil inteiro), com defeito (quarentena) e válidas. Todos os satélites passam: quem escolhe qual
 * conta no IFC é a regra de cálculo, não a carga.
 */
public class ValidadorFoco {

    /** Código IBGE dos municípios do Paraná começa com 41. */
    static final String PREFIXO_IBGE_PR = "41";

    /** Caixa do Paraná com folga (a malha do IBGE vai de -54,62 a -48,02 e de -26,72 a -22,52). */
    static final double MIN_LON = -54.7, MAX_LON = -47.9, MIN_LAT = -26.8, MAX_LAT = -22.4;

    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]");

    public record FocoValido(String idInpe, LocalDateTime dataHora, String satelite, String codIbge,
                             String municipio, String bioma, BigDecimal frp, double lon, double lat) {}

    public record Resultado(FocoLido foco, FocoValido valido, String motivoQuarentena, boolean foraDoRecorte) {
        public boolean aprovado() {
            return valido != null;
        }
    }

    public Resultado validar(FocoLido foco) {
        if (!doParana(foco)) {
            return new Resultado(foco, null, null, true);
        }

        if (vazio(foco.idInpe())) {
            return quarentena(foco, "id vazio");
        }
        if (vazio(foco.satelite())) {
            return quarentena(foco, "satelite vazio");
        }

        Double lon = numero(foco.lon());
        Double lat = numero(foco.lat());
        if (lon == null || lat == null) {
            return quarentena(foco, "coordenada inválida");
        }
        // Linha marcada como Paraná com ponto fora dele: dado inconsistente, não descarte
        if (lon < MIN_LON || lon > MAX_LON || lat < MIN_LAT || lat > MAX_LAT) {
            return quarentena(foco, "coordenada fora do Paraná");
        }

        LocalDateTime dataHora;
        try {
            dataHora = LocalDateTime.parse(foco.dataHora() == null ? "" : foco.dataHora().trim(), DATA_HORA);
        } catch (DateTimeParseException e) {
            return quarentena(foco, "data_hora_gmt inválida");
        }

        Double frp = numero(foco.frp());
        return new Resultado(foco, new FocoValido(
                foco.idInpe().trim(), dataHora, foco.satelite().trim(),
                codIbge(foco.codIbge()), foco.municipio(), foco.bioma(),
                frp == null ? null : BigDecimal.valueOf(frp), lon, lat), null, false);
    }

    /** Pelo código IBGE do município; sem ele, pelo nome do estado ("PARANÁ"). */
    static boolean doParana(FocoLido foco) {
        String cod = codIbge(foco.codIbge());
        if (cod != null) {
            return cod.startsWith(PREFIXO_IBGE_PR);
        }
        return foco.estado() != null && semAcento(foco.estado()).equals("PARANA");
    }

    /** O INPE grava o código como número: "4119905" ou às vezes "4119905.0". */
    private static String codIbge(String valor) {
        if (vazio(valor)) {
            return null;
        }
        String s = valor.trim();
        int ponto = s.indexOf('.');
        return ponto < 0 ? s : s.substring(0, ponto);
    }

    private static Double numero(String valor) {
        if (vazio(valor)) {
            return null;
        }
        try {
            return Double.valueOf(valor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String semAcento(String texto) {
        return Normalizer.normalize(texto.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);
    }

    private static boolean vazio(String s) {
        return s == null || s.isBlank();
    }

    private static Resultado quarentena(FocoLido foco, String motivo) {
        return new Resultado(foco, null, motivo, false);
    }
}
