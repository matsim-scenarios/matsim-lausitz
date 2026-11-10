library(tidyverse)

money_events <- read_csv2(
  # gzfile("D:/public-svn/matsim/scenarios/countries/de/lausitz/projects/DiTriMo/v2.0/02_drt-case-study/no-pooling-pt-fare/output-1-ruhland-bhf_full_plans/1-ruhland-bhf_full_plans.output_personMoneyEvents.tsv.gz")
  # gzfile("D:/public-svn/matsim/scenarios/countries/de/lausitz/projects/DiTriMo/v2.0/02_drt-case-study/no-pooling-pt-fare/output-2-ruhland-bhf-spremberg-bhf_full_plans/2-ruhland-bhf-spremberg-bhf_full_plans.output_personMoneyEvents.tsv.gz")
  # gzfile("D:/public-svn/matsim/scenarios/countries/de/lausitz/projects/DiTriMo/v2.0/02_drt-case-study/no-pooling-pt-fare/output-3-ruhland-bhf-spremberg-bhf-cottbus-bhf_full_plans/3-ruhland-bhf-spremberg-bhf-cottbus-bhf_full_plans.output_personMoneyEvents.tsv.gz")
  # gzfile("D:/public-svn/matsim/scenarios/countries/de/lausitz/projects/DiTriMo/v2.0/02_drt-case-study/no-pooling-pt-fare/output-4-ruhland-bhf-spremberg-bhf-schwarze-pumpe_full_plans/4-ruhland-bhf-spremberg-bhf-schwarze-pumpe_full_plans.output_personMoneyEvents.tsv.gz")
  gzfile("D:/public-svn/matsim/scenarios/countries/de/lausitz/projects/DiTriMo/v2.0/02_drt-case-study/no-pooling-pt-fare/output-5-regional-drt_full_plans/5-regional-drt_full_plans.output_personMoneyEvents.tsv.gz")
) %>% 
  mutate(person = as.character(person),
         amount = as.numeric(amount))

refunds <- money_events %>% 
  filter(str_detect(purpose, "refund"))

unique(refunds$purpose)                          

drt_money <- money_events %>% 
  filter(str_detect(purpose, "drt"))

refunds_drt <- refunds %>% 
  filter(person %in% drt_money$person)

drt_money <- bind_rows(drt_money, refunds_drt)

drt_money_sum <- drt_money %>%
  group_by(person) %>%
  summarise(
    amount = sum(amount, na.rm = TRUE),
    .groups = "drop"
  )

mean(drt_money_sum$amount)
