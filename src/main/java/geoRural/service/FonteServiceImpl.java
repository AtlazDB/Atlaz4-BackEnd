package geoRural.service;

import geoRural.dto.FonteRequest;
import geoRural.entity.Fonte;
import geoRural.exception.FonteNaoEncontradaException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.exception.SiglaDuplicadaException;
import geoRural.repository.FonteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class FonteServiceImpl implements FonteService {

    private static final List<String> FORMATOS = List.of("CSV", "GeoJSON", "SHP", "XLSX", "JSON");
    private static final int SIGLA_MAX = 12;
    private static final List<String> MODALIDADES = List.of("MANUAL", "LINK");
    private static final List<String> METODOS = List.of("GET", "POST");
    private static final int URL_MAX = 1000;  // url_origem, V8

    private final FonteRepository repository;

    public FonteServiceImpl(FonteRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Fonte> listar(String busca) {
        return vazio(busca)
                ? repository.findAllByOrderByCriadaEmDesc()
                : repository.buscar(busca.trim());
    }

    @Override
    @Transactional
    public Fonte cadastrar(FonteRequest request) {
        validar(request);

        String sigla = request.sigla().trim().toUpperCase();
        if (repository.existsBySiglaIgnoreCase(sigla)) {
            throw new SiglaDuplicadaException(request.sigla().trim());
        }

        Fonte fonte = new Fonte();
        fonte.setNome(request.nome().trim());
        fonte.setSigla(sigla);
        fonte.setOrgao(request.orgao().trim());
        fonte.setFormato(request.formato().trim());
        fonte.setPeriodicidade(vazio(request.periodicidade()) ? "eventual" : request.periodicidade().trim());

        // --- Novos campos mapeados (Task T1 - Ingestão por Link e Execução) ---
        fonte.setModalidade(vazio(request.modalidade()) ? "MANUAL" : request.modalidade().trim().toUpperCase());
        fonte.setUrlOrigem(request.urlOrigem() != null ? request.urlOrigem().trim() : null);
        fonte.setMetodoHttp(vazio(request.metodoHttp()) ? "GET" : request.metodoHttp().trim().toUpperCase());
        fonte.setCabecalhos(request.cabecalhos());
        fonte.setCredencialRef(request.credencialRef() != null ? request.credencialRef().trim() : null);
        fonte.setCorpoRequisicao(request.corpoRequisicao());
        fonte.setPaginacao(request.paginacao() != null ? request.paginacao().trim() : null);
        fonte.setCron(request.cron() != null ? request.cron().trim() : null);

        return repository.save(fonte);
    }

    @Override
    public Fonte buscarPorId(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new FonteNaoEncontradaException(id));
    }

    private void validar(FonteRequest request) {
        List<String> faltando = new ArrayList<>();
        if (vazio(request.nome()))    faltando.add("nome");
        if (vazio(request.sigla()))   faltando.add("sigla");
        if (vazio(request.orgao()))   faltando.add("orgao");
        if (vazio(request.formato())) faltando.add("formato");
        if (!faltando.isEmpty()) {
            throw new RequisicaoInvalidaException("Preencha: " + String.join(", ", faltando) + ".", faltando);
        }

        if (request.sigla().trim().length() > SIGLA_MAX) {
            throw new RequisicaoInvalidaException(
                    "A sigla deve ter no máximo " + SIGLA_MAX + " caracteres.", List.of("sigla"));
        }

        if (!FORMATOS.contains(request.formato().trim())) {
            throw new RequisicaoInvalidaException(
                    "Formato inválido. Use: " + String.join(", ", FORMATOS) + ".", List.of("formato"));
        }

        validarLink(request);
    }

    /**
     * Fonte por link: o link é obrigatório e precisa ser http ou https. Aqui só a forma: se o
     * servidor existe e se o endereço é público, quem confere é a coleta (ValidadorUrlExterna),
     * na hora de buscar, porque isso muda com o tempo.
     */
    private void validarLink(FonteRequest request) {
        String modalidade = vazio(request.modalidade()) ? "MANUAL" : request.modalidade().trim().toUpperCase(Locale.ROOT);
        if (!MODALIDADES.contains(modalidade)) {
            throw new RequisicaoInvalidaException(
                    "Modalidade inválida. Use: " + String.join(", ", MODALIDADES) + ".", List.of("modalidade"));
        }
        if (!vazio(request.metodoHttp()) && !METODOS.contains(request.metodoHttp().trim().toUpperCase(Locale.ROOT))) {
            throw new RequisicaoInvalidaException("Método HTTP inválido. Use GET ou POST.", List.of("metodoHttp"));
        }
        if (!"LINK".equals(modalidade)) {
            return;
        }
        if (vazio(request.urlOrigem())) {
            throw new RequisicaoInvalidaException("Uma fonte por link precisa do link de origem.", List.of("urlOrigem"));
        }
        String url = request.urlOrigem().trim();
        if (url.length() > URL_MAX) {
            throw new RequisicaoInvalidaException(
                    "O link pode ter no máximo " + URL_MAX + " caracteres.", List.of("urlOrigem"));
        }
        try {
            URI uri = new URI(url);
            String esquema = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(esquema.equals("http") || esquema.equals("https")) || vazio(uri.getHost())) {
                throw new URISyntaxException(url, "esquema ou servidor");
            }
        } catch (URISyntaxException e) {
            throw new RequisicaoInvalidaException(
                    "O link precisa ser um endereço http:// ou https:// completo.", List.of("urlOrigem"));
        }
    }

    private static boolean vazio(String s) {
        return s == null || s.isBlank();
    }
}
