# [📱📲: BlueHeaven: Bluetooth Low Energy-based mesh network for general-purpose traffic](https://github.com/regulad/BlueHeaven)

![Successful connection across 5 nodes](https://imgur.com/a/jJsaunP)

## Design Overview

BlueHeaven was born when I came into possession of around 15 Android phones and decided that I wanted to do something with them. 

Inspired by Silicon Valley's PiperNet, I quickly came up with the idea to implement a mesh network, and I wrote up my ideas here: https://regulad.notion.site/BlueHeaven-bb83b532feed4d0e9c896d2cc4130d3d?pvs=4

Based on the [B.A.T.M.A.N.](https://en.wikipedia.org/wiki/B.A.T.M.A.N.) routing design, my nodes exchange information on the next hop to reach a destination, meaning that no single node has to know the entire network topology, which was critical as my network was going to have even bigger scalability issues than a traditional mesh network with Bluetooth devices joining and leaving reguarly.

## Implementation Woes

Although I had a lot of fun writing the code, I ran into several issues that I was able to solve.

* Android's Bluetooth LE API is only available on Lolipop (5.0) and above. This means that I can't use my older phones, which limits the maximum size of my network. I was left with only five phones to test on.
* In addition, the API requires a device to be equipped with a Bluetooth 4.2 transmitter, which is rare among old phones, even those running Lolipop. My Samsung Galaxy S5s and Moto G4 Plays were compatible, but others were not.
* The API also needs better documentation, altogether omitting information on some features implemented transparently, like Hardware Address Randomization, which was a nightmare to debug.
* Android Devices can only pair to a maximum of 7 devices at a time, which means that without any master planning of the topology, the mesh will eventually struggle to grow as devices favor existing connections. I implemented a system to drop connections after a certain amount of time, but I hypothesize that at scale, this won't be enough.
* Occasionally, the BLE GATT server (the main method of moving data in large quantities for BLE) would refuse to start up, even between app restarts. For absolutely no good reason, restarting the device would fix this issue. I could not find a solution to this problem, as it only happened on my Moto G4 Plays after extended testing. It's likely a poor vendor implementation, and I suspect I would run into additional problems as I tested on more devices.
* The client that connects to the server is extremely unreliable and often disconnects for no reason. 
* While reconnection logic was easy, BLE is restricted to creating one connection per device before behavior becomes unpredictable.
* **Even making only one connection at a time, connection to the GATT servers was still extremely unreliable. Requests regularly shot above the 60-second timeout.**

The final problem proved to be the most fatal. Occasionally, the client would be able to connect with no problem, but completely randomly it would not be able to send data to the server. After 5+ hours of debugging, I was not able to find even a single lead towards why this was happening. Multiple devices, multiple Android versions, multiple BLE chipsets, and multiple connection methods were all tried, but the problem persisted. Eventually, I stumbed across the medium series [Making Android BLE Work](https://medium.com/@martijn.van.welie/making-android-ble-work-part-3-117d3a8aee23) and I was able to put it all together. I need to thank Martijn van Welie endlessly as his series was the only thing that helped me solve my problem. 

![BLE Connection Diagram](https://media.discordapp.net/attachments/1251004997118722078/1279614616246423653/IMG_2557.jpg?ex=66d5157a&is=66d3c3fa&hm=c50b18af737a2c215e9dcd3da3ae7925900bb67eea8122c61346f64c34048099&=&format=webp&width=904&height=678)

> A picture from a version where devices had no limit on the number of servers they could connect to. You could see that they *really* favored connecting to as many peers as possible. However, you can see one of the devices along the top with knowledge about nodes it isn't directly connected to. This is the OGM routing algorithm at work!
