// Prints what the Vulkan loader of the guest sees: instance version, physical devices with their driver and API
// versions. Run as a shell binary: adb push vkprobe /data/local/tmp && adb shell /data/local/tmp/vkprobe
// Build: testapps/vkprobe/build.sh (NDK clang, x86_64, API 33). 12.10.2026.
#include <stdio.h>
#include <vulkan/vulkan.h>

int main(void) {
    uint32_t api = 0;
    vkEnumerateInstanceVersion(&api);
    printf("instance API %u.%u.%u\n", VK_VERSION_MAJOR(api), VK_VERSION_MINOR(api), VK_VERSION_PATCH(api));
    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO, NULL, "vkprobe", 1, NULL, 0, VK_API_VERSION_1_1};
    VkInstanceCreateInfo ci = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, NULL, 0, &app, 0, NULL, 0, NULL};
    VkInstance inst;
    VkResult r = vkCreateInstance(&ci, NULL, &inst);
    printf("vkCreateInstance: %d\n", r);
    if (r != VK_SUCCESS) return 1;
    uint32_t n = 0;
    r = vkEnumeratePhysicalDevices(inst, &n, NULL);
    printf("vkEnumeratePhysicalDevices: %d, count %u\n", r, n);
    VkPhysicalDevice devs[8];
    if (n > 8) n = 8;
    vkEnumeratePhysicalDevices(inst, &n, devs);
    for (uint32_t i = 0; i < n; i++) {
        VkPhysicalDeviceProperties p;
        vkGetPhysicalDeviceProperties(devs[i], &p);
        printf("device %u: %s, type %d, API %u.%u.%u, driver 0x%x, vendor 0x%x\n", i, p.deviceName, p.deviceType,
               VK_VERSION_MAJOR(p.apiVersion), VK_VERSION_MINOR(p.apiVersion), VK_VERSION_PATCH(p.apiVersion),
               p.driverVersion, p.vendorID);
    }
    vkDestroyInstance(inst, NULL);
    return 0;
}
