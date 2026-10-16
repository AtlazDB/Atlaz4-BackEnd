package geoRural.service;

import java.util.HashSet;
import java.util.Set;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.GeometryFixer;

import geoRural.dto.ImovelLido;

/**
 * Valida e corrige os imóveis de UM arquivo. Guarda os códigos já vistos para achar duplicados,
 * por isso crie uma instância nova para cada arquivo_bruto processado (não é um bean do Spring).
 */
public class ValidadorImovel {

    /**
     * SRID gravado no banco. As coordenadas do CAR estão em SIRGAS 2000 (EPSG:4674), não em
     * WGS 84 (EPSG:4326). A diferença entre os dois é menor que 1 metro, abaixo da precisão do
     * próprio CAR, então gravamos as coordenadas como vieram, marcadas como 4326, sem reprojetar.
     * Decisão consciente: reprojetar custaria processamento e não mudaria nenhum resultado.
     */
    static final int SRID = 4326;

    /** Aprovado: {@code geometria} corrigida e {@code motivoQuarentena} nulo. Senão, o contrário. */
    public record Resultado(ImovelLido imovel, Geometry geometria, String motivoQuarentena) {
        public boolean aprovado() {
            return motivoQuarentena == null;
        }
    }

    private final Set<String> codigosVistos = new HashSet<>();

    public Resultado validar(ImovelLido imovel) {
        String cod = imovel.codImovel();
        if (cod == null || cod.isBlank()) {
            return quarentena(imovel, "cod_imovel vazio");
        }
        if (!codigosVistos.add(cod)) {
            return quarentena(imovel, "cod_imovel duplicado");
        }

        Geometry g = imovel.geometria();
        if (g == null || g.isEmpty()) {
            return quarentena(imovel, "sem geometria");
        }

        g = prepararGeometria(g);
        if (g == null) {
            return quarentena(imovel, "geometria irreparável");
        }

        return new Resultado(imovel, g, null);
    }

    /**
     * Corrige a geometria, deixa os anéis no sentido do Oracle e marca o SRID. Devolve null se
     * não sobrar um polígono válido. Também usada na Reserva Legal, que vem do mesmo SICAR.
     */
    static Geometry prepararGeometria(Geometry original) {
        // copy() para não alterar a geometria de quem chamou (normalize() muda o objeto no lugar)
        Geometry g = original.isValid() ? original.copy() : GeometryFixer.fix(original);
        if (g.isEmpty() || !g.isValid() || !(g instanceof Polygon || g instanceof MultiPolygon)) {
            return null;
        }

        // Mesma lição da V6: o Oracle exige anel externo anti-horário e o shapefile guarda horário.
        g.normalize();   // JTS: anel externo horário, buracos anti-horário
        g = g.reverse(); // inverte → anel externo anti-horário, como o Oracle espera
        g.setSRID(SRID);
        return g;
    }

    private static Resultado quarentena(ImovelLido imovel, String motivo) {
        return new Resultado(imovel, null, motivo);
    }
}
