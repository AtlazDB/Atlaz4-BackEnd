package geoRural.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import geoRural.exception.ShapefileInvalidoException;

class LeitorShapefileTest {

    private final LeitorShapefile leitor = new LeitorShapefile();

    @TempDir
    Path tmp;

    @Test
    void extraiTodosOsShpComComplementos() throws IOException {
        Path zip = zip(Map.of(
                "PR/norte.shp", "", "PR/norte.shx", "", "PR/norte.dbf", "", "PR/norte.prj", "",
                "SUL.SHP", "", "sul.shx", "", "sul.dbf", "", "sul.prj", ""));

        try (var extraidos = leitor.extrair(zip)) {
            assertEquals(2, extraidos.shapefiles().size());
            assertTrue(Files.exists(extraidos.pasta()));
        }
    }

    @Test
    void apagaPastaTemporariaAoFechar() throws IOException {
        Path zip = zip(Map.of("a.shp", "", "a.shx", "", "a.dbf", "", "a.prj", ""));

        Path pasta;
        try (var extraidos = leitor.extrair(zip)) {
            pasta = extraidos.pasta();
        }
        assertFalse(Files.exists(pasta));
    }

    @Test
    void rejeitaQuandoFaltaComplemento() throws IOException {
        Path zip = zip(Map.of("a.shp", "", "a.shx", "", "a.dbf", ""));

        var erro = assertThrows(ShapefileInvalidoException.class, () -> leitor.extrair(zip));
        assertTrue(erro.getMessage().contains("a.prj"));
    }

    @Test
    void rejeitaZipSemShp() throws IOException {
        Path zip = zip(Map.of("leiame.txt", "nada"));

        assertThrows(ShapefileInvalidoException.class, () -> leitor.extrair(zip));
    }

    @Test
    void rejeitaZipSlip() throws IOException {
        Path zip = zip(Map.of("../../fora.txt", "malicioso"));

        assertThrows(ShapefileInvalidoException.class, () -> leitor.extrair(zip));
    }

    /**
     * Para olhar um arquivo real (passo 5):
     * mvnw.cmd test -Dtest=LeitorShapefileTest "-Dshapefile.zip=C:\caminho\municipio.zip"
     */
    @Test
    @EnabledIfSystemProperty(named = "shapefile.zip", matches = ".+")
    void olharArquivoReal() {
        Path zip = Path.of(System.getProperty("shapefile.zip"));
        var validador = new ValidadorImovel();
        Map<String, Integer> porResultado = new TreeMap<>();
        try (var extraidos = leitor.extrair(zip)) {
            System.out.println("Arquivos .shp: " + extraidos.shapefiles());
            for (Path shp : extraidos.shapefiles()) {
                leitor.imprimirEstrutura(shp);
                AtomicInteger total = new AtomicInteger();
                leitor.ler(shp, imovel -> {
                    var resultado = validador.validar(imovel);
                    String chave = resultado.aprovado()
                            ? "aprovado (" + resultado.geometria().getGeometryType() + ")"
                            : "quarentena: " + resultado.motivoQuarentena();
                    porResultado.merge(chave, 1, Integer::sum);

                    if (total.incrementAndGet() <= 3 || !resultado.aprovado()) {
                        System.out.printf("linha %d | %s | %s | %s ha | %s | %s | %s%n",
                                imovel.linha(), imovel.codImovel(), imovel.municipio(),
                                imovel.areaHa(), imovel.situacao(),
                                imovel.geometria() == null ? "sem geometria"
                                        : imovel.geometria().getGeometryType() + ", "
                                        + imovel.geometria().getNumPoints() + " pontos",
                                chave);
                    }
                });
                System.out.println(shp.getFileName() + ": " + total.get() + " imóveis lidos");
            }
        }
        System.out.println("Resumo da validação:");
        porResultado.forEach((chave, qtd) -> System.out.println("  " + chave + ": " + qtd));
    }

    private Path zip(Map<String, String> arquivos) throws IOException {
        Path zip = Files.createTempFile(tmp, "teste-", ".zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            for (var arquivo : arquivos.entrySet()) {
                z.putNextEntry(new ZipEntry(arquivo.getKey()));
                z.write(arquivo.getValue().getBytes());
                z.closeEntry();
            }
        }
        return zip;
    }
}
