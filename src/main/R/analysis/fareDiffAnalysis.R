library(tidyverse)
library(optparse)


########################################## input params #########################################################################

option_list <- list(
  make_option(c("-r", "--runDir"), type="character", default=NULL,
              help="Path to drt run directory. Avoid using '/', use '/' instead.", metavar="character"))

opt_parser <- OptionParser(option_list=option_list)
opt <- parse_args(opt_parser)

if (is.null(opt$runDir)) {
  print_help(opt_parser)
  stop("Error: --runDir is required", call.=FALSE)
}

run_dir <- opt$runDir
run_dir_fixed <- gsub("////", "/", run_dir)

# if you do not want to use opt_parse, comment out the above lines starting from option_list <- ...
# you have to define run_dir_fixed yourself
# run_dir_fixed <- "Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-0-fare/output-4-ruhland-bhf-spremberg-bhf-schwarze-pumpe"

setwd(run_dir_fixed)
print(paste("Running analysis on run dir", getwd()))

money_events_path <- list.files(path=run_dir_fixed, pattern="output_personMoneyEvents\\.tsv\\.gz$", full.names = TRUE)

############################################## get the data ##################################################################

money_events_policy <- read_delim(file = money_events_path,delim = ";", col_types = cols(
    time = col_double(),
    person = col_character(),
    amount = col_double(),
    purpose = col_character(),
    transactionPartner = col_character(),
    reference = col_character()))

money_events_base <- read_delim(file = "Y:/net/ils/matsim-lausitz/v2.0-release/base-case-ctd-10-pct/output-lausitz-v2.0-10pct-base-case-ctd-commit-430893a/lausitz-v2.0-10pct-base-case-ctd.output_personMoneyEvents.tsv.gz",
  delim = ";", col_types = cols(
    time = col_double(),
    person = col_character(),
    amount = col_double(),
    purpose = col_character(),
    transactionPartner = col_character(),
    reference = col_character()))

############################################# analysis ####################################################

# person money events file could include other events than caused by pt fare
filtered_policy <- money_events_policy %>% 
  filter(str_detect(purpose, "fare"))

# get sum of fares. multiply negative values with -1 to avoid knot in brain
sum_fares_policy <- filtered_policy %>% 
  filter(!str_detect(purpose, "refund")) %>%
  mutate(amount=amount * -1) %>%
  summarise(total = sum(amount)) %>% 
  pull(total)

sum_refunds_policy <- filtered_policy %>% 
  filter(str_detect(purpose, "refund")) %>% 
  summarise(total = sum(amount)) %>% 
  pull(total)

# because of multiplication of fares with -1: total real paid fares: paid_fares - refunds
total_policy <- sum_fares_policy - sum_refunds_policy

# person money events file could include other events than caused by pt fare
filtered_base <- money_events_base %>% 
  filter(str_detect(purpose, "fare"))

# get sum of fares. multiply negative values with -1 to avoid knot in brain
sum_fares_base <- filtered_base %>% 
  filter(!str_detect(purpose, "refund")) %>% 
  mutate(amount=amount * -1) %>%
  summarise(total = sum(amount)) %>% 
  pull(total)

sum_refunds_base <- filtered_base %>% 
  filter(str_detect(purpose, "refund")) %>%
  summarise(total = sum(amount)) %>% 
  pull(total)

# because of multiplication of fares with -1: total real paid fares: paid_fares - refunds
total_base <- sum_fares_base - sum_refunds_base

diff <- total_policy - total_base

mean_fare_base <- total_base / length(unique(filtered_base$person))
mean_fare_policy <- total_policy / length(unique(filtered_policy$person))

df <- data.frame(
  param = c("sum_fares_base_euro", "sum_refunds_base_euro", "total_base_euro", "mean_fare_base_euro", "sum_fares_policy_euro", "sum_refunds_policy_euro", "total_policy_euro", "mean_fare_policy_euro", "total_diff_euro"),
  value = c(sum_fares_base, -sum_refunds_base, total_base, mean_fare_base, sum_fares_policy, -sum_refunds_policy, total_policy, mean_fare_policy, diff)
)

write.csv(df, "fare_comparison_to_base_case.csv", quote=FALSE, row.names = FALSE)
print(paste("fare comparison to base case stats written to", getwd()))
