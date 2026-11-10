library(tidyverse)
library(ggokabeito)

###################################### income distr plots #############################################################################################################

# read data from pt fare cases
income_groups_pt_fare_1 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-pt-fare/output-1-ruhland-bhf_full_plans/analysis/analysis/drt_persons_income_groups.csv") %>%
  mutate(Case = "Ruhland") 
income_groups_pt_fare_2 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-pt-fare/output-2-ruhland-bhf-spremberg-bhf_full_plans/analysis/analysis/drt_persons_income_groups.csv") %>%
  mutate(Case = "Ruhland-Spremberg") 
income_groups_pt_fare_3 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-pt-fare/output-3-ruhland-bhf-spremberg-bhf-cottbus-bhf_full_plans/analysis/analysis/drt_persons_income_groups.csv") %>%
  mutate(Case = "Ruhland-Spremberg-\nCottbus")
income_groups_pt_fare_4 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-pt-fare/output-4-ruhland-bhf-spremberg-bhf-schwarze-pumpe_full_plans/analysis/analysis/drt_persons_income_groups.csv") %>%
  mutate(Case = "Ruhland-Spremberg-\nSchwarze Pumpe") 
income_groups_pt_fare_5 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-pt-fare/output-5-regional-drt_full_plans/analysis/analysis/drt_persons_income_groups.csv") %>%
  mutate(Case = "Regional DRT")
# read data from 0 fare cases
income_groups_0_fare_1 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-0-fare/output-1-ruhland-bhf_full_plans/analysis/analysis/drt_persons_income_groups.csv") %>%
  mutate(Case = "Ruhland")
income_groups_0_fare_2 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-0-fare/output-2-ruhland-bhf-spremberg-bhf_full_plans/analysis/analysis/drt_persons_income_groups.csv") %>%
  mutate(Case = "Ruhland-Spremberg")
income_groups_0_fare_3 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-0-fare/output-3-ruhland-bhf-spremberg-bhf-cottbus-bhf_full_plans/analysis/analysis/drt_persons_income_groups.csv") %>%
  mutate(Case = "Ruhland-Spremberg-\nCottbus")
income_groups_0_fare_4 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-0-fare/output-4-ruhland-bhf-spremberg-bhf-schwarze-pumpe_full_plans/analysis/analysis/drt_persons_income_groups.csv") %>%
  mutate(Case = "Ruhland-Spremberg-\nSchwarze Pumpe")
income_groups_0_fare_5 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-0-fare/output-5-regional-drt_full_plans/analysis/analysis/drt_persons_income_groups.csv") %>%
  mutate(Case = "Regional DRT")
income_groups_general <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-pt-fare/output-1-ruhland-bhf_full_plans/analysis/analysis/all_persons_income_groups.csv") %>%
  mutate(Case = "All agents")

incomeGroups <- unique(income_groups_pt_fare_1$incomeGroup)

# Combine datasets
combined_pt_fare <- bind_rows(income_groups_pt_fare_1, income_groups_pt_fare_2, income_groups_pt_fare_3, income_groups_pt_fare_4, income_groups_pt_fare_5, income_groups_general) %>%
  mutate(incomeGroup = factor(incomeGroup, levels = incomeGroups)) %>% 
  mutate(Case = factor(Case, levels = unique(Case)))
combined_0_fare <- bind_rows(income_groups_0_fare_1, income_groups_0_fare_2, income_groups_0_fare_3, income_groups_0_fare_4, income_groups_0_fare_5, income_groups_general) %>%
  mutate(incomeGroup = factor(incomeGroup, levels = incomeGroups)) %>%
  mutate(Case = factor(Case, levels = unique(Case)))

# plot income distr
income_distr_pt_fare <- ggplot(combined_pt_fare, aes(x = incomeGroup, y = share, fill = Case)) +
  geom_col(
    data = subset(combined_pt_fare, Case != "All agents"),
    position = position_dodge(width = 0.8), width = 0.7
  ) +
  geom_col(
    data = subset(combined_pt_fare, Case == "All agents"),
    alpha = 0.4, width = 0.95, show.legend = TRUE
  ) +
  scale_fill_okabe_ito(order = c(1,2,3,4,5,6)) +
  labs(x = "Income group [€]", y = "Share") +
  theme_minimal() +
  theme(
    plot.title = element_text(hjust = 0.5, size = 20),
    axis.title = element_text(size = 15),
    axis.text = element_text(size = 14),
    axis.text.x = element_text(angle = 90, vjust = 0.5, hjust = 1),
    legend.title = element_text(size = 15),
    legend.text = element_text(size = 12),
    plot.margin = margin(5, 5, 5, 5)
  )

income_distr_0_fare <- ggplot(combined_0_fare, aes(x = incomeGroup, y = share, fill = Case)) +
  geom_col(
    data = subset(combined_0_fare, Case != "All agents"),
    position = position_dodge(width = 0.8), width = 0.7
  ) +
  geom_col(
    data = subset(combined_0_fare, Case == "All agents"),
    alpha = 0.4, width = 0.95, show.legend = TRUE
  ) +
  scale_fill_okabe_ito(order = c(1,2,3,4,5,6)) +
  labs(x = "Income group [€]", y = "Share") +
  theme_minimal() +
  theme(
    plot.title = element_text(hjust = 0.5, size = 20),
    axis.title = element_text(size = 15),
    axis.text = element_text(size = 14),
    axis.text.x = element_text(angle = 90, vjust = 0.5, hjust = 1),
    legend.title = element_text(size = 15),
    legend.text = element_text(size = 12),
    plot.margin = margin(5, 5, 5, 5),
    legend.position = "none"
  )

income_distr_pt_fare
income_distr_0_fare

# save to pdf with high resolution
ggsave("income_distr_pt_fare.pdf", income_distr_pt_fare, dpi = 500, w = 9, h = 9)
ggsave("income_distr_0_fare.pdf", income_distr_0_fare, dpi = 500, w = 7, h = 9)

###################################### modal shift to drt plots #############################################################################################################

# read data from pt fare cases
base_modes_pt_fare_1 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-pt-fare/output-1-ruhland-bhf_full_plans/analysis/analysis/drt_persons_base_modal_share.csv") %>%
  mutate(Case = "Ruhland") 
base_modes_pt_fare_2 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-pt-fare/output-2-ruhland-bhf-spremberg-bhf_full_plans/analysis/analysis/drt_persons_base_modal_share.csv") %>%
  mutate(Case = "Ruhland-Spremberg") 
base_modes_pt_fare_3 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-pt-fare/output-3-ruhland-bhf-spremberg-bhf-cottbus-bhf_full_plans/analysis/analysis/drt_persons_base_modal_share.csv") %>%
  mutate(Case = "Ruhland-Spremberg-\nCottbus")
base_modes_pt_fare_4 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-pt-fare/output-4-ruhland-bhf-spremberg-bhf-schwarze-pumpe_full_plans/analysis/analysis/drt_persons_base_modal_share.csv") %>%
  mutate(Case = "Ruhland-Spremberg-\nSchwarze Pumpe") 
base_modes_pt_fare_5 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-pt-fare/output-5-regional-drt_full_plans/analysis/analysis/drt_persons_base_modal_share.csv") %>%
  mutate(Case = "Regional DRT")
# read data from 0 fare cases
base_modes_0_fare_1 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-0-fare/output-1-ruhland-bhf_full_plans/analysis/analysis/drt_persons_base_modal_share.csv") %>%
  mutate(Case = "Ruhland")
base_modes_0_fare_2 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-0-fare/output-2-ruhland-bhf-spremberg-bhf_full_plans/analysis/analysis/drt_persons_base_modal_share.csv") %>%
  mutate(Case = "Ruhland-Spremberg")
base_modes_0_fare_3 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-0-fare/output-3-ruhland-bhf-spremberg-bhf-cottbus-bhf_full_plans/analysis/analysis/drt_persons_base_modal_share.csv") %>%
  mutate(Case = "Ruhland-Spremberg-\nCottbus")
base_modes_0_fare_4 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-0-fare/output-4-ruhland-bhf-spremberg-bhf-schwarze-pumpe_full_plans/analysis/analysis/drt_persons_base_modal_share.csv") %>%
  mutate(Case = "Ruhland-Spremberg-\nSchwarze Pumpe")
base_modes_0_fare_5 <- read_csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-0-fare/output-5-regional-drt_full_plans/analysis/analysis/drt_persons_base_modal_share.csv") %>%
  mutate(Case = "Regional DRT")

modes <- unique(base_modes_pt_fare_5$main_mode)

# Combine datasets
combined_base_modes_pt_fare <- bind_rows(base_modes_pt_fare_1, base_modes_pt_fare_2, base_modes_pt_fare_3, base_modes_pt_fare_4, base_modes_pt_fare_5) %>%
  mutate(main_mode = factor(main_mode, levels = modes)) %>%
  mutate(Case = factor(Case, levels = unique(Case)))
combined_base_modes_0_fare <- bind_rows(base_modes_0_fare_1, base_modes_0_fare_2, base_modes_0_fare_3, base_modes_0_fare_4, base_modes_0_fare_5) %>%
  mutate(main_mode = factor(main_mode, levels = modes)) %>%
  mutate(Case = factor(Case, levels = unique(Case)))

base_modes_pt_fare <- ggplot(combined_base_modes_pt_fare, aes(x = main_mode, y = share, fill = Case)) +
  geom_col(
    data = subset(combined_base_modes_pt_fare, Case != "All agents"),
    position = position_dodge(width = 0.8), width = 0.7
  ) +
  scale_fill_okabe_ito(order = c(1,2,3,4,5)) +
  labs(x = "Main mode", y = "Share") +
  theme_minimal() +
  theme(
    plot.title = element_text(hjust = 0.5, size = 20),
    axis.title = element_text(size = 15),
    axis.text = element_text(size = 14),
    axis.text.x = element_text(angle = 90, vjust = 0.5, hjust = 1),
    legend.title = element_text(size = 15),
    legend.text = element_text(size = 12),
    plot.margin = margin(5, 5, 5, 5)
  )
base_modes_0_fare <- ggplot(combined_base_modes_0_fare, aes(x = main_mode, y = share, fill = Case)) +
  geom_col(
    data = subset(combined_base_modes_0_fare, Case != "All agents"),
    position = position_dodge(width = 0.8), width = 0.7
  ) +
  scale_fill_okabe_ito(order = c(1,2,3,4,5)) +
  labs(x = "Main mode", y = "Share") +
  theme_minimal() +
  theme(
    plot.title = element_text(hjust = 0.5, size = 20),
    axis.title = element_text(size = 15),
    axis.text = element_text(size = 14),
    axis.text.x = element_text(angle = 90, vjust = 0.5, hjust = 1),
    legend.title = element_text(size = 15),
    legend.text = element_text(size = 12),
    plot.margin = margin(5, 5, 5, 5),
    legend.position = "none"
  )

base_modes_pt_fare
base_modes_0_fare

# save to pdf with high resolution
ggsave("base_modes_pt_fare.pdf", base_modes_pt_fare, dpi = 500, w = 9, h = 9)
ggsave("base_modes_0_fare.pdf", base_modes_0_fare, dpi = 500, w = 7, h = 9)

trips <- read_csv(file="C:/Users/Simon/Desktop/wd/2025-08-18/drt-trips-ruhland.csv")
unique(trips$longest_distance_mode)

