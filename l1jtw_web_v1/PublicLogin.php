<?
require("setup.php");
$page=$_GET[page];
$err_count=0;
$msg="";

$onepageannouncecount=6;
$chcmin=0;

if($page==NULL){$page=1;}

$str="select count(*) from zwls_announce where ok=1";
$chk_rps=$db->get_var($str);
if($chk_rps==0){
	$msg=$msg."尚無公告<br>";
}

if($err_count==0){
	$chcmin=$onepageannouncecount*($page-1);

	$pagemax=$chk_rps/$onepageannouncecount;
	$pagemax=(int)$pagemax;

	$ckpagemax=$pagemax*$onepageannouncecount;

	if($ckpagemax<$chk_rps){
		$pagemax=$pagemax+1;
	}

	if($pagemax>=5){
		$pagemax=5;
	}

	$pagea=1;
	$pageb=0;

	While($pagea<=$pagemax){
		if($pageb==5){
			$pageb=0;
			$pagetb=$pagetb."<br>";
		}
	$pagetb=$pagetb."<a href=\"PublicLogin.php?page=$pagea\">第 $pagea 頁</a> ";
	$pagea++;
	$pageb++;
	}

	if($chk_rps!=0){
		mysql_select_db($sql_dbname, $login_on);
		$straa = sprintf("SELECT * FROM zwls_announce WHERE `ok` LIKE 1 ORDER BY `no` DESC LIMIT $chcmin,$onepageannouncecount");
		$strab = mysql_query($straa, $login_on) or die(mysql_error());
		$strac = mysql_fetch_assoc($strab);
	}
}
?>
<html>

<head>
<meta http-equiv="Content-Language" content="zh-tw">
<meta http-equiv="Content-Type" content="text/html; charset=utf-8">
<title><?=$web_name?></title>
</head>

<body bgcolor="#000000" text="#FFFFFF" link="#FFFFFF" vlink="#FFFFFF" alink="#FFFFFF">
<center>
<table border=0 cellpadding=1 cellspacing=1 bgcolor=ffffff width="100%">
<?if($err_count==0){?>
<tr><td width="100%" bgcolor=#303030 colspan="5"><div align="center"><font size=2 color=red>遊戲公告</font></div></td></tr>
<?if($chk_rps!=0){?>
<tr>
<td width="10%" bgcolor=#303030><div align="center">NO.</div></td>
<td width="50%" bgcolor=#303030><div align="center">公告名稱</div></td>
<td width="30%" bgcolor=#303030><div align="center">公告日期</div></td>
<td width="10%" bgcolor=#303030><div align="center">查看</div></td>
</tr>
<?do{?>
<tr>
<td bgcolor=#202020><div align="center" class="style7"><?=$strac['no']?></div></td>
<td bgcolor=#202020><div align="center" class="style7"><?=$strac['announcename']?></a></font></div></td>
<td bgcolor=#202020><div align="center" class="style7"><?=$strac['datetime']?></div></td>
<td bgcolor=#202020><div align="center" class="style7"><a href="javascript://" onClick="window.open('announce/announceforPublicLogin.php?no=<?=$strac['no']?>','','menubar=no,status=no,scrollbars=yes,top=20,left=50,toolbar=no,width=600,height=400')"><font color="#ffffff">查看</a></tr>
<?
}while($strac = mysql_fetch_assoc($strab));
?>
<tr><td width="100%" bgcolor=#303030 colspan="5"><div align="center"><?=$pagetb?></div></td></tr>
<?
}else{
?>
<tr><td width="100%" bgcolor=#101010><div align="left"><?=$msg?></div></td></tr>
<?
}
}else{
?>
<tr><td width="100%" bgcolor=#303030><div align="center"><font size=2 color=red>系統訊息</font></div></td></tr>
<tr><td width="100%" bgcolor=#202020><div align="left"><?=$msg?></div></td></tr>
<?}?>
</table>
</center>
</body></html>
