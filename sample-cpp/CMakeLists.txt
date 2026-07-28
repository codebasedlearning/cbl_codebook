# The C++ sample project
cmake_minimum_required(VERSION 3.20)
project(cbl_sample_cpp CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Header-only helpers, shared by every snippet
add_library(utils INTERFACE)
target_include_directories(utils INTERFACE ${CMAKE_CURRENT_SOURCE_DIR}/snippets)

# One executable per snippet
add_executable(staticmethod snippets/staticmethod.cpp)
target_link_libraries(staticmethod PRIVATE utils)
