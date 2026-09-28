package geoRural.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.feature.type.AttributeDescriptor;
import org.geotools.api.feature.type.GeometryDescriptor;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import geoRural.dto.ImovelLido;
import geoRural.exception.ShapefileInvalidoException;

@Component
public class LeitorShapefile {

    private static final Logger log = LoggerFactory.getLogger(LeitorShapefile.class);

    // Nomes das colunas no .dbf do CAR. Confira com imprimirEstrutura() e ajuste se vierem diferentes.
    // A busca ignora maiúsculas/minúsculas.
    static final String COLUNA_COD_IMOVEL = "cod_imovel";
    static final String COLUNA_MUNICIPIO = "municipio";
    static final String COLUNA_AREA = "num_area";
    static final String COLUNA_SITUACAO = "ind_status";

    private static final List<String> COMPLEMENTOS_OBRIGATORIOS = List.of("shx", "dbf", "prj");
    private static final Charset CHARSET_PADRAO = Charset.forName("ISO-8859-1");

    /** Pasta temporária com o conteúdo do zip. Apaga tudo ao fechar (use em try-with-resources). */
    public record ShapefilesExtraidos(Path pasta, List<Path> shapefiles) implements AutoCloseable {
        @Override
        public void close() {
            try (Stream<Path> caminhos = Files.walk(pasta)) {
                caminhos.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                log.warn("Não foi possível apagar a pasta temporária {}", pasta, e);
            }
        }
    }

    // ---------------------------------------------------------------- passo 4

    /**
     * Descompacta o .zip numa pasta temporária e devolve todos os .shp encontrados
     * (o CAR às vezes divide o estado em mais de um). Cada .shp precisa ter .shx, .dbf e .prj
     * com o mesmo nome; se faltar algum, o arquivo inteiro é rejeitado.
     */
    public ShapefilesExtraidos extrair(Path zip) {
        Path pasta;
        try {
            pasta = Files.createTempDirectory("shp-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ShapefilesExtraidos extraidos = null;
        try {
            descompactar(zip, pasta);
            List<Path> shapefiles = procurarShapefiles(pasta);
            extraidos = new ShapefilesExtraidos(pasta, shapefiles);
            return extraidos;
        } finally {
            // Se deu erro no meio do caminho, não deixa lixo no disco
            if (extraidos == null) {
                new ShapefilesExtraidos(pasta, List.of()).close();
            }
        }
    }

    private void descompactar(Path zip, Path pasta) {
        Path raiz = pasta.toAbsolutePath().normalize();
        try (ZipFile arquivoZip = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entradas = arquivoZip.entries();
            while (entradas.hasMoreElements()) {
                ZipEntry entrada = entradas.nextElement();
                Path destino = raiz.resolve(entrada.getName()).normalize();

                // Proteção contra "zip slip": um nome como ../../algo escreveria fora da pasta
                if (!destino.startsWith(raiz)) {
                    throw new ShapefileInvalidoException(
                            "Entrada do zip aponta para fora da pasta de extração: " + entrada.getName());
                }

                if (entrada.isDirectory()) {
                    Files.createDirectories(destino);
                    continue;
                }
                Files.createDirectories(destino.getParent());
                try (InputStream in = arquivoZip.getInputStream(entrada)) {
                    Files.copy(in, destino);
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException: nome de entrada com codificação inválida
            throw new ShapefileInvalidoException("Não foi possível descompactar o arquivo: " + e.getMessage(), e);
        }
    }

    private List<Path> procurarShapefiles(Path pasta) {
        List<Path> shapefiles;
        try (Stream<Path> caminhos = Files.walk(pasta)) {
            shapefiles = caminhos
                    .filter(Files::isRegularFile)
                    .filter(p -> extensao(p).equalsIgnoreCase("shp"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (shapefiles.isEmpty()) {
            throw new ShapefileInvalidoException("Nenhum arquivo .shp encontrado dentro do zip");
        }

        List<String> faltando = new ArrayList<>();
        for (Path shp : shapefiles) {
            for (String ext : COMPLEMENTOS_OBRIGATORIOS) {
                if (complemento(shp, ext).isEmpty()) {
                    faltando.add(nomeSemExtensao(shp) + "." + ext);
                }
            }
        }
        if (!faltando.isEmpty()) {
            throw new ShapefileInvalidoException("Arquivos obrigatórios ausentes: " + String.join(", ", faltando));
        }
        return shapefiles;
    }

    /** Arquivo irmão do .shp com a extensão pedida, ignorando maiúsculas (CAR.SHP + car.dbf). */
    private Optional<Path> complemento(Path shp, String ext) {
        String base = nomeSemExtensao(shp);
        try (Stream<Path> irmaos = Files.list(shp.getParent())) {
            return irmaos
                    .filter(p -> nomeSemExtensao(p).equalsIgnoreCase(base))
                    .filter(p -> extensao(p).equalsIgnoreCase(ext))
                    .findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- passo 5

    /** Só para desenvolvimento: mostra as colunas reais do .dbf e o sistema de coordenadas. */
    public void imprimirEstrutura(Path shp) {
        ShapefileDataStore store = abrir(shp);
        try {
            log.info("Charset: {}", store.getCharset());
            log.info("Estrutura de {}: {}", shp.getFileName(), store.getSchema());
            log.info("CRS: {}", store.getSchema().getCoordinateReferenceSystem());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            store.dispose();
        }
    }

    /**
     * Lê os imóveis um por um e entrega cada um ao consumidor, sem carregar o arquivo inteiro
     * na memória.
     */
    public void ler(Path shp, Consumer<ImovelLido> consumidor) {
        ShapefileDataStore store = abrir(shp);
        try {
            SimpleFeatureType schema = store.getSchema();
            String colunaCod = coluna(schema, COLUNA_COD_IMOVEL)
                    .orElseThrow(() -> new ShapefileInvalidoException(
                            shp.getFileName() + " não tem a coluna " + COLUNA_COD_IMOVEL));
            String colunaMunicipio = coluna(schema, COLUNA_MUNICIPIO).orElse(null);
            String colunaArea = coluna(schema, COLUNA_AREA).orElse(null);
            String colunaSituacao = coluna(schema, COLUNA_SITUACAO).orElse(null);

            try (SimpleFeatureIterator it = store.getFeatureSource().getFeatures().features()) {
                int linha = 0;
                while (it.hasNext()) {
                    SimpleFeature f = it.next();
                    linha++;
                    consumidor.accept(new ImovelLido(
                            linha,
                            texto(f, colunaCod),
                            texto(f, colunaMunicipio),
                            numero(f, colunaArea),
                            texto(f, colunaSituacao),
                            (Geometry) f.getDefaultGeometry(),
                            atributos(f)));
                }
            }
        } catch (IOException e) {
            throw new ShapefileInvalidoException("Erro ao ler " + shp.getFileName() + ": " + e.getMessage(), e);
        } finally {
            store.dispose();
        }
    }

    private ShapefileDataStore abrir(Path shp) {
        try {
            ShapefileDataStore store = new ShapefileDataStore(shp.toUri().toURL());
            store.setCharset(charset(shp));
            return store;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Charset do .dbf, lido do .cpg. Sem .cpg (ou com conteúdo desconhecido), ISO-8859-1.
     * Se os acentos dos municípios vierem quebrados, o problema está aqui.
     */
    Charset charset(Path shp) {
        Optional<Path> cpg = complemento(shp, "cpg");
        if (cpg.isEmpty()) {
            return CHARSET_PADRAO;
        }
        String nome;
        try {
            nome = Files.readString(cpg.get(), StandardCharsets.US_ASCII).trim();
        } catch (IOException e) {
            return CHARSET_PADRAO;
        }
        // O ArcGIS às vezes grava só o número da code page, ex.: "1252"
        if (nome.matches("\\d+")) {
            nome = "windows-" + nome;
        }
        try {
            return Charset.forName(nome);
        } catch (IllegalArgumentException e) {
            log.warn("Charset desconhecido no .cpg ({}); usando {}", nome, CHARSET_PADRAO);
            return CHARSET_PADRAO;
        }
    }

    private static Optional<String> coluna(SimpleFeatureType schema, String nome) {
        return schema.getAttributeDescriptors().stream()
                .map(d -> d.getLocalName())
                .filter(n -> n.equalsIgnoreCase(nome))
                .findFirst();
    }

    private static String texto(SimpleFeature f, String coluna) {
        if (coluna == null) {
            return null;
        }
        Object valor = f.getAttribute(coluna);
        if (valor == null) {
            return null;
        }
        String s = valor.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal numero(SimpleFeature f, String coluna) {
        if (coluna == null) {
            return null;
        }
        Object valor = f.getAttribute(coluna);
        if (valor instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (valor instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Todas as colunas do .dbf, menos a geometria. */
    private static Map<String, Object> atributos(SimpleFeature f) {
        Map<String, Object> atributos = new LinkedHashMap<>();
        for (AttributeDescriptor d : f.getFeatureType().getAttributeDescriptors()) {
            if (!(d instanceof GeometryDescriptor)) {
                atributos.put(d.getLocalName(), f.getAttribute(d.getLocalName()));
            }
        }
        return atributos;
    }

    private static String extensao(Path p) {
        String nome = p.getFileName().toString();
        int ponto = nome.lastIndexOf('.');
        return ponto < 0 ? "" : nome.substring(ponto + 1);
    }

    private static String nomeSemExtensao(Path p) {
        String nome = p.getFileName().toString();
        int ponto = nome.lastIndexOf('.');
        return ponto < 0 ? nome : nome.substring(0, ponto);
    }
}
