cmake_minimum_required(VERSION 3.22.1)
project(terminal C)

add_library(terminal-pty SHARED pty.c)
target_compile_options(terminal-pty PRIVATE -Wall -O2)
