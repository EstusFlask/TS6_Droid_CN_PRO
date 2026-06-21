cmake_minimum_required(VERSION 3.22.1)

project(tsdroid_audio)

add_library(
    tsdroid_audio
    SHARED
    rnnoise_bridge.c
    rnnoise/celt_lpc.c
    rnnoise/denoise.c
    rnnoise/kiss_fft.c
    rnnoise/pitch.c
    rnnoise/rnn.c
    rnnoise/rnn_data.c
    rnnoise/rnn_reader.c
)

target_compile_definitions(tsdroid_audio PRIVATE RNNOISE_BUILD)
target_include_directories(tsdroid_audio PRIVATE rnnoise)
target_compile_options(tsdroid_audio PRIVATE -O3)

find_library(log-lib log)

target_link_libraries(tsdroid_audio PRIVATE m ${log-lib})
