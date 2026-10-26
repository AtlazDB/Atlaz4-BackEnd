# Memória de cálculo — IRL e IFC

Como o Oracle chega aos valores de **IRL** (Índice de Reserva Legal) e **IFC** (Índice de Focos de Calor) de cada imóvel, e como conferir um caso no QGIS.

O cálculo fica no package `pkg_indicadores` (`R__pkg_indicadores.sql`). As regras e os parâmetros ficam na tabela `regra_calculo` (`V12__indicadores_irl_ifc.sql`). Para recalcular, use `POST /api/v1/pipeline/indicadores`. Para consultar, use `GET /api/v1/imoveis/{codImovel}/indicadores`.

## Regras vigentes

| | IRL | IFC |
|---|---|---|
| Código | `RN-IRL-Min20pct` v1 | `RN-IFC-Queimadas` v1 |
| Fórmula | Σ área de RL declarada (ha) ÷ área declarada do imóvel (ha) × 100 | focos dentro do imóvel na janela ÷ área declarada do imóvel (ha) × 1000 |
| Unidade | % | focos/1000ha |
| Parâmetros | `percentual_minimo` = 20 | `janela_meses` = 12, `satelite` = AQUA_M-T, `por_hectares` = 1000, `limite_critico` = 20 |
| Status | ≥ 20%: Conforme; < 20%: Crítico | 0 focos: Conforme; < 20: Alerta; ≥ 20: Crítico |
| Extra | saldo (ha) = área RL − 20% da área do imóvel | — |

Os valores são arredondados em 4 casas. Os parâmetros são lidos do JSON da regra na hora do cálculo. Para mudar um limite, cadastra-se uma nova versão da regra, e os cálculos antigos continuam apontando para a versão que usaram.

## Decisões

- **Área do imóvel**: a área usada é a declarada no CAR (`num_area`), não a área calculada do polígono. É o número que o produtor declarou e que a fiscalização usa.
- **Mínimo de 20%**: vale para Mata Atlântica e Cerrado fora da Amazônia Legal (Lei 12.651/2012, art. 12), o que cobre todo o Paraná.
- **Cobertura do IRL**: o IRL só é calculado para imóveis de municípios que vieram em algum arquivo de RL. Se o município está coberto e o imóvel não tem nenhuma RL declarada, o resultado é 0% (Crítico). Se o município não tem RL carregada, o imóvel fica sem IRL, porque faltaria dado e não seria um déficit.
- **Satélite de referência**: um mesmo incêndio é detectado uma vez por cada satélite que passa, e somar todos inflaria a contagem. Usamos só o AQUA_M-T, que é o satélite da série histórica do INPE. Conferimos que o arquivo `focos_br_ref_2025` do INPE é 100% AQUA_M-T.
- **Janela**: são os 12 meses anteriores ao momento do cálculo, contados em UTC, já que `data_hora_gmt` do INPE é UTC. Só entram os focos carregados no banco. Se só um mês foi carregado, a janela tem só esse mês.
- **Foco dentro do imóvel**: usamos `SDO_RELATE(..., 'mask=ANYINTERACT')`, então um ponto exatamente na divisa conta.
- **Referencial**: o CAR vem em SIRGAS 2000 (EPSG:4674) e é gravado como 4326. A diferença para o WGS84 é de centímetros, irrelevante nessa escala.
- **Recorte**: do CSV nacional do INPE só são gravados os focos do Paraná (`municipio_id` começando em 41). Os demais são contados como "fora do recorte" e não vão para a quarentena.

## Rastreabilidade

Cada linha de `indicador_imovel` guarda:

- `regra_calculo_id`: a regra e a versão usadas.
- `versao_imovel_id`: a versão do CAR (conjunto `imoveis_rurais`) que deu o contorno e a área.
- `dataset_versao_id`: a versão do insumo, `reserva_legal` no IRL e `focos_calor` no IFC.
- `numerador`, `area_imovel_ha`, `area_ha` (saldo) e `calculado_em`.

A partir das versões chega-se à execução, ao arquivo da zona bruta e ao hash SHA-256 dele. A tela **Rastreabilidade** do front mostra esse caminho.

## Exemplo real — IFC

Imóvel `PR-4102901-944EADD4424041428AF7C626EC298A25`, em Bituruna:

- CAR: `AREA_IMOVEL_BITURUNA.zip`, baixado do WFS do SICAR.
- Focos: `focos_mensal_br_202609.zip`, do INPE (setembro de 2026).
- Área declarada: **83,1286 ha**.

No incêndio de 26/09/2026 houve 13 detecções em 4 satélites ao redor do imóvel (arquivo [exemplo-ifc-focos.geojson](exemplo-ifc-focos.geojson)). Duas delas são do AQUA_M-T, e só uma cai dentro do contorno:

| id | data/hora (UTC) | lon | lat | dentro? |
|---|---|---|---|---|
| b714f575-f303-3897-ba14-8abe1d55fa2a | 2026-09-26 19:04 | -51.47385 | -26.28567 | sim |
| 53ece049-… | 2026-09-26 19:04 | -51.45883 | -26.28394 | não |

```
IFC = 1 ÷ 83,1286 × 1000 = 12,0296 focos/1000ha  →  Alerta (entre 0 e 20)
```

O Oracle devolveu `12.0296`, `ALERTA`, `numerador = 1`.

Também conferimos todos os imóveis de Bituruna com um ponto-em-polígono independente, feito em Python. Os resultados bateram com o Oracle em 1993 de 1993 imóveis válidos.

## Exemplo real — IRL

Imóvel `PR-4102901-3524774BFF3C4EB88C2DBE4D661DA2C6`, em Bituruna:

- RL: `PR_Bituruna_RESERVA_LEGAL.gpkg`, baixado da Base de Downloads do SICAR e convertido para shapefile.
- Área declarada: **29,121 ha**.
- Duas áreas de Reserva Legal (arquivos [exemplo-irl-imovel.geojson](exemplo-irl-imovel.geojson) e [exemplo-irl-reserva-legal.geojson](exemplo-irl-reserva-legal.geojson)):

| área de RL | num_area (ha) |
|---|---|
| 1 | 0,9634 |
| 2 | 3,0893 |
| **soma** | **4,0527** |

```
IRL   = 4,0527 ÷ 29,121 × 100     = 13,9168 %  →  Crítico (< 20%)
saldo = 4,0527 − 29,121 × 20 ÷ 100 = −1,7715 ha  (déficit)
```

O Oracle devolveu `13.9168`, `CRITICO`, saldo `-1.7715`.

Conferimos os 1994 imóveis de Bituruna somando a `num_area` da RL direto do arquivo do SICAR. IRL e saldo bateram com o Oracle em todos. O resultado foi 799 imóveis Conformes e 1195 Críticos.

O SICAR entrega a RL em GeoPackage. Como o processamento da fonte `CAR-RL` lê shapefile, o arquivo é convertido antes do envio: no QGIS, em *Exportar → Salvar feições como… → ESRI Shapefile*, e depois os arquivos `.shp`, `.shx`, `.dbf`, `.prj` e `.cpg` são compactados num `.zip`. Os nomes das colunas (`cod_imovel`, `num_area`, `municipio`) já vêm certos.

## Exemplo sintético — IRL

Este caso é coberto pelo teste `IndicadorCalculoTest`:

- Imóvel com 100,5 ha declarados.
- Duas áreas de RL que somam 15,5 ha.

```
IRL   = 15,5 ÷ 100,5 × 100       = 15,4229 %  →  Crítico (< 20%)
saldo = 15,5 − 100,5 × 20 ÷ 100  = −4,6 ha   (déficit)
```

## Conferência no QGIS

### IFC

1. Carregue [exemplo-ifc-imovel.geojson](exemplo-ifc-imovel.geojson) e [exemplo-ifc-focos.geojson](exemplo-ifc-focos.geojson). Para outro imóvel, exporte o contorno pela API (`GET /api/v1/imoveis/mapa`) ou pelo shapefile do CAR, e use o CSV mensal do INPE como camada de texto delimitado (X = `lon`, Y = `lat`, EPSG:4326).
2. Filtre os focos (botão direito → *Filtrar…*) assim:
   `"satelite" = 'AQUA_M-T' AND "data_hora_gmt" >= '<início da janela>'`
3. Rode *Vetor → Analisar → Contar pontos em polígonos* com o imóvel como polígono e os focos filtrados como pontos.
4. Calcule `NUMPOINTS / num_area * 1000` na tabela de atributos e compare com o IFC.

### IRL

1. Carregue [exemplo-irl-imovel.geojson](exemplo-irl-imovel.geojson) e [exemplo-irl-reserva-legal.geojson](exemplo-irl-reserva-legal.geojson). Para outro imóvel, use o GPKG de Reserva Legal do SICAR para o município.
2. Filtre `"cod_imovel" = '<código>'` e some `num_area`: *Vetor → Analisar → Estatísticas básicas para campos*, campo `num_area`, soma.
3. Divida a soma pela `num_area` do imóvel (camada de imóveis) e multiplique por 100. O saldo é `soma − num_area_imóvel × 0,2`.

Some a `num_area` declarada da RL, não a área do polígono calculada pelo QGIS. É ela que o cálculo usa.
