import { BlockList, isIPv6 } from 'net'

// LAN, this machine, and Tailscale (100.64.0.0/10, fd7a:…). Everything else counts as the internet.
const PRIVATE = new BlockList()
PRIVATE.addSubnet('127.0.0.0', 8, 'ipv4')
PRIVATE.addSubnet('10.0.0.0', 8, 'ipv4')
PRIVATE.addSubnet('172.16.0.0', 12, 'ipv4')
PRIVATE.addSubnet('192.168.0.0', 16, 'ipv4')
PRIVATE.addSubnet('100.64.0.0', 10, 'ipv4')
PRIVATE.addAddress('::1', 'ipv6')
PRIVATE.addSubnet('fc00::', 7, 'ipv6')
PRIVATE.addSubnet('fe80::', 10, 'ipv6')

export const isPrivateAddress = (ip) => !!ip && PRIVATE.check(ip, isIPv6(ip) ? 'ipv6' : 'ipv4')
