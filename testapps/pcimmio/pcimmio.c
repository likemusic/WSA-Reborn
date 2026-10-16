// pcimmio: read/write 32-bit registers of a PCI BAR through sysfs (Android has no /dev/mem).
//   pcimmio <sysfs resourceN> <offset> [<value>]   — prints the register (after the write, if any)
// Needs root. Built with the NDK: testapps/pcimmio/build.sh. 16.10.2026.
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <sys/mman.h>
#include <unistd.h>

int main(int argc, char** argv)
{
    if (argc < 3) { fprintf(stderr, "usage: pcimmio <resourceN> <offset> [<value>]\n"); return 2; }
    int fd = open(argv[1], O_RDWR | O_SYNC);
    if (fd < 0) { perror(argv[1]); return 1; }
    unsigned long off = strtoul(argv[2], NULL, 0);
    volatile uint8_t* p = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, (off & ~4095UL));
    if (p == MAP_FAILED) { perror("mmap"); return 1; }
    volatile uint32_t* r = (volatile uint32_t*)(p + (off & 4095));
    if (argc > 3) *r = (uint32_t)strtoul(argv[3], NULL, 0);
    printf("0x%08x\n", *r);
    return 0;
}
