library(matsim)
library(tidyverse)
library(readr)
library(sf)

#FILES
FILE_DIR = "C:/Users/ACER/Desktop/Uni/VSP/Lausitz-Plots/"
SIM <- paste0(FILE_DIR, "lausitz-v1.0-commuter.csv")
GEMEINDE <- paste0(FILE_DIR, "pgemeinden.csv")
COMMUTER <- paste0(FILE_DIR, "commuter.csv")
SHP <- paste0(FILE_DIR, "VG5000_GEM/VG5000_GEM.shp")
LAUSITZ.SHP <- paste0(FILE_DIR, "network-area/network-area.shp")

shp <- st_read(SHP)
lausitz.shp <- st_read(LAUSITZ.SHP)

sim <- read_csv(file = SIM)
gemeinden <- read_csv( file = GEMEINDE) %>%
  mutate(code = str_remove(string = code, pattern = "P"))

commuter <- read_csv(file = COMMUTER) %>%
  mutate(key = paste0(from, "-", to))

sim.1 <- sim %>%
  filter(!is.na(from) & !is.na(to)) %>%
  left_join(gemeinden, by = c("from" = "code")) %>%
  left_join(gemeinden, by = c("from" = "code"), suffix = c("_from", "_to")) %>%
  mutate(key = paste0(from, "-", to)) %>%
  select(-c(from, to)) %>%
  left_join(commuter, by = "key", suffix = c("_sim", "_real")) %>%
  filter(!is.na(from)) %>%
#  pivot_longer(cols = starts_with("n_"), names_to = "src", values_to = "n", names_prefix = "n_") %>%
  arrange(desc(n_real))

breaks <- c(-Inf, 0.8, 1.2, Inf)
labels <- c("less", "exakt", "more")

sim.2 <- sim.1 %>%
  filter(n_sim > 10) %>%
  select(from, to, starts_with("n_"), starts_with("name_")) %>%
  mutate(n_rel = n_sim / n_real,
         quality = cut(n_rel, breaks = breaks, labels = labels))

ggplot(sim.2, aes(x = n_real, y = n_sim, col = quality)) +
  
  geom_point() +
  
  scale_x_log10() +
  
  scale_y_log10() +
  
  theme_bw()
