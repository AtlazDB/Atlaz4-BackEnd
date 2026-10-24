package geoRural.mapeamento.service;

import geoRural.entity.Fonte;
import geoRural.exception.ColunaNaoEncontradaException;
import geoRural.exception.MapeamentoDuplicadoException;
import geoRural.exception.MapeamentoNaoEncontradoException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.mapeamento.dto.MapeamentoRequest;
import geoRural.mapeamento.entity.Dominio;
import geoRural.mapeamento.entity.FonteColuna;
import geoRural.mapeamento.entity.ReferenciaCruzada;
import geoRural.mapeamento.entity.RegraCampo;
import geoRural.mapeamento.repository.DominioRepository;
import geoRural.mapeamento.repository.FonteColunaRepository;
import geoRural.mapeamento.repository.RegraCampoRepository;
import geoRural.service.FonteService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class MapeamentoServiceImpl implements MapeamentoService {

    // Mesmos valores dos CHECKs de regra_campo (V9)
    private static final List<String> TIPOS =
            List.of("TEXTO", "INTEIRO", "DECIMAL", "DATA", "BOOLEANO", "GEOMETRIA");
    private static final List<String> POLITICAS = List.of("QUARENTENA", "CORRIGIR_E_REGISTRAR");
    private static final String GEOMETRIA = "GEOMETRIA";

    private static final int NOME_MAX = 60; // destino_tabela, campo_destino e formato_origem
    // Letras minúsculas, números e _, começando por letra. O pkg_quality pode usar esses nomes em SQL
    // dinâmico, então nada além disso é aceito.
    private static final Pattern IDENTIFICADOR = Pattern.compile("^[a-z][a-z0-9_]*$");

    private final FonteService fonteService;
    private final RegraCampoRepository regraRepository;
    private final FonteColunaRepository colunaRepository;
    private final DominioRepository dominioRepository;

    public MapeamentoServiceImpl(FonteService fonteService,
                                 RegraCampoRepository regraRepository,
                                 FonteColunaRepository colunaRepository,
                                 DominioRepository dominioRepository) {
        this.fonteService = fonteService;
        this.regraRepository = regraRepository;
        this.colunaRepository = colunaRepository;
        this.dominioRepository = dominioRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RegraCampo> listar(Long fonteId) {
        fonteService.buscarPorId(fonteId); // 404 se a fonte não existir
        return regraRepository.listarPorFonte(fonteId);
    }

    @Override
    @Transactional
    public RegraCampo criar(Long fonteId, MapeamentoRequest request) {
        Fonte fonte = fonteService.buscarPorId(fonteId); // 404 se a fonte não existir

        RegraCampo regra = montarEValidar(request); // 400 se os dados forem inválidos

        FonteColuna coluna = colunaRepository.buscarDaFonte(request.colunaId(), fonteId)
                .orElseThrow(ColunaNaoEncontradaException::new);

        Dominio dominio = null;
        if (request.dominioId() != null) {
            dominio = dominioRepository.buscarPorIdEAtivo(request.dominioId(), true)
                    .orElseThrow(() -> new RequisicaoInvalidaException(
                            "Domínio não encontrado ou inativo.", List.of("dominioId")));
        }

        if (regraRepository.existePorColuna(coluna.getId())) {
            throw new MapeamentoDuplicadoException("A coluna \"" + coluna.getNomeColuna() + "\" já está mapeada.");
        }
        if (regraRepository.existePorDestino(fonteId, regra.getDestinoTabela(), regra.getCampoDestino())) {
            throw new MapeamentoDuplicadoException("O campo " + regra.getDestinoTabela() + "."
                    + regra.getCampoDestino() + " já recebe outra coluna desta fonte.");
        }

        regra.setFonte(fonte);
        regra.setFonteColuna(coluna);
        regra.setDominio(dominio);

        try {
            return regraRepository.saveAndFlush(regra);
        } catch (DataIntegrityViolationException e) {
            // duas requisições ao mesmo tempo: a restrição do banco (UNIQUE) pegou o que a checagem acima não viu
            throw new MapeamentoDuplicadoException("Esta coluna ou este campo de destino já foi mapeado.");
        }
    }

    @Override
    @Transactional
    public void remover(Long fonteId, Long mapeamentoId) {
        fonteService.buscarPorId(fonteId); // 404 se a fonte não existir
        RegraCampo regra = regraRepository.buscarDaFonte(mapeamentoId, fonteId)
                .orElseThrow(MapeamentoNaoEncontradoException::new);
        regraRepository.delete(regra);
    }

    /** Valida o corpo e devolve uma regra com os campos simples já normalizados (sem fonte, coluna nem domínio). */
    private RegraCampo montarEValidar(MapeamentoRequest request) {
        List<String> faltando = new ArrayList<>();
        if (request.colunaId() == null)           faltando.add("colunaId");
        if (vazio(request.destinoTabela()))       faltando.add("destinoTabela");
        if (vazio(request.campoDestino()))        faltando.add("campoDestino");
        if (vazio(request.tipoDado()))            faltando.add("tipoDado");
        if (!faltando.isEmpty()) {
            throw new RequisicaoInvalidaException("Preencha: " + String.join(", ", faltando) + ".", faltando);
        }

        String tabela = minusculo(request.destinoTabela());
        String campo = minusculo(request.campoDestino());
        validarIdentificador("destinoTabela", tabela);
        validarIdentificador("campoDestino", campo);

        String tipo = request.tipoDado().trim().toUpperCase(Locale.ROOT);
        if (!TIPOS.contains(tipo)) {
            throw new RequisicaoInvalidaException(
                    "Tipo de dado inválido. Use: " + String.join(", ", TIPOS) + ".", List.of("tipoDado"));
        }

        boolean obrigatorio = Boolean.TRUE.equals(request.obrigatorio());
        boolean chave = Boolean.TRUE.equals(request.chave());
        boolean recortarPr = Boolean.TRUE.equals(request.recortarPr());

        if (chave && !obrigatorio) {
            throw new RequisicaoInvalidaException(
                    "Uma coluna chave precisa ser obrigatória.", List.of("chave", "obrigatorio"));
        }

        String politica = vazio(request.politicaGeometria())
                ? null : request.politicaGeometria().trim().toUpperCase(Locale.ROOT);
        if (GEOMETRIA.equals(tipo)) {
            if (politica == null || !POLITICAS.contains(politica)) {
                throw new RequisicaoInvalidaException(
                        "Para geometria, informe a política: " + String.join(" ou ", POLITICAS) + ".",
                        List.of("politicaGeometria"));
            }
        } else {
            if (politica != null) {
                throw new RequisicaoInvalidaException(
                        "A política de geometria só vale para colunas do tipo GEOMETRIA.",
                        List.of("politicaGeometria"));
            }
            if (recortarPr) {
                throw new RequisicaoInvalidaException(
                        "A conferência do Paraná (recortarPr) só vale para colunas do tipo GEOMETRIA.",
                        List.of("recortarPr"));
            }
        }

        String referencia = vazio(request.referenciaCruzada()) ? null : minusculo(request.referenciaCruzada());
        if (referencia != null && !ReferenciaCruzada.existe(referencia)) {
            String validas = Arrays.stream(ReferenciaCruzada.values())
                    .map(ReferenciaCruzada::getCodigo)
                    .collect(Collectors.joining(", "));
            throw new RequisicaoInvalidaException(
                    "Referência cruzada inválida. Use: " + validas + ".", List.of("referenciaCruzada"));
        }

        String formato = vazio(request.formatoOrigem()) ? null : request.formatoOrigem().trim();
        if (formato != null && formato.length() > NOME_MAX) {
            throw new RequisicaoInvalidaException(
                    "O formato de origem deve ter no máximo " + NOME_MAX + " caracteres.",
                    List.of("formatoOrigem"));
        }

        RegraCampo regra = new RegraCampo();
        regra.setDestinoTabela(tabela);
        regra.setCampoDestino(campo);
        regra.setTipoDado(tipo);
        regra.setFormatoOrigem(formato);
        regra.setObrigatorio(obrigatorio);
        regra.setChave(chave);
        regra.setBloquearRegressao(Boolean.TRUE.equals(request.bloquearRegressao()));
        regra.setValidarCpfCnpj(Boolean.TRUE.equals(request.validarCpfCnpj()));
        regra.setPoliticaGeometria(politica);
        regra.setRecortarPr(recortarPr);
        regra.setReferenciaCruzada(referencia);
        return regra;
    }

    private static void validarIdentificador(String campo, String valor) {
        if (valor.length() > NOME_MAX) {
            throw new RequisicaoInvalidaException(
                    "O campo " + campo + " deve ter no máximo " + NOME_MAX + " caracteres.", List.of(campo));
        }
        if (!IDENTIFICADOR.matcher(valor).matches()) {
            throw new RequisicaoInvalidaException(
                    "O campo " + campo + " deve usar só letras minúsculas, números e _, começando por letra.",
                    List.of(campo));
        }
    }

    private static String minusculo(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean vazio(String s) {
        return s == null || s.isBlank();
    }
}