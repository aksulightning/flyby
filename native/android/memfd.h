#pragma once
#include <sys/mman.h>
int flyby_memfd_create(const char *, unsigned int);
#define memfd_create flyby_memfd_create
